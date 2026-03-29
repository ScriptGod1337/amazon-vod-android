package com.scriptgod.fireos.avod.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.scriptgod.fireos.avod.model.AudioTrack

/**
 * Selects the best [AudioTrack] metadata entry for each live ExoPlayer track and builds
 * the final [TrackOption] list consumed by the audio menu.
 *
 * All string formatting and family classification is delegated to [AudioTrackLabeler].
 */
@UnstableApi
class AudioTrackResolver(private var availableAudioTracks: List<AudioTrack>) {

    companion object {
        private const val TAG = "AudioTrackResolver"
    }

    data class TrackOption(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val familyKind: String,
        val isSelected: Boolean,
        val isAudioDescription: Boolean,
        val bitrate: Int,
        val language: String = ""
    )

    data class AudioLiveCandidate(
        val option: TrackOption,
        val normalizedLanguage: String,
        val rawLabel: String,
        val channelCount: Int
    )

    data class AudioResolvedOption(
        val option: TrackOption,
        val familyKey: String
    )

    data class AudioMetadataFamily(
        val familyKind: String,
        val label: String,
        val isAudioDescription: Boolean
    )

    var lastLoggedAudioTrackSignature: String = ""
    var lastLoggedAudioResolutionSignature: String = ""

    fun updateAvailableTracks(tracks: List<AudioTrack>) {
        availableAudioTracks = tracks
    }

    fun getAvailableAudioTracks(): List<AudioTrack> = availableAudioTracks

    fun buildTrackOptions(trackType: Int, tracks: Tracks): List<TrackOption> {
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return buildAudioTrackOptions(tracks)
        }

        val groups = tracks.groups.filter { it.type == trackType }
        val options = mutableListOf<TrackOption>()

        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val isAd = trackType == C.TRACK_TYPE_AUDIO &&
                    AudioTrackLabeler.isAudioDescriptionTrack(format)
                val label = AudioTrackLabeler.buildTrackLabel(trackType, format, isAd)
                options += TrackOption(
                    group = group,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = label,
                    familyKind = "text",
                    isSelected = group.isTrackSelected(trackIndex),
                    isAudioDescription = isAd,
                    bitrate = format.bitrate
                )
            }
        }

        val bestByLabel = linkedMapOf<String, TrackOption>()
        for (option in options) {
            val key = option.label
            val existing = bestByLabel[key]
            val better = existing == null ||
                (option.isSelected && !existing.isSelected) ||
                (!existing.isSelected && option.bitrate > existing.bitrate)
            if (better) bestByLabel[key] = option
        }

        return bestByLabel.values.sortedWith(
            compareBy<TrackOption> { if (it.isAudioDescription) 1 else 0 }
                .thenBy { it.label.lowercase() }
        )
    }

    fun buildAudioTrackOptions(tracks: Tracks): List<TrackOption> {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val resolved = mutableListOf<TrackOption>()
        val resolutionEntries = mutableListOf<String>()
        val languageVariantCounts = mutableMapOf<String, Int>()

        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val normalizedLanguage = AudioTrackLabeler.normalizeAudioGroupLanguage(format.language)
                val rawLabel = format.label?.trim().orEmpty()
                // Use MPD Role flag (set by MpdTimingCorrector for _descriptive AdaptationSets)
                // as the authoritative AD signal before metadata lookup.
                val mpdIsAd = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
                // Keep AD and non-AD ordinals independent so MPD track ordering (e.g.
                // [dialog, descriptive, boost-medium]) doesn't shift the non-AD counter.
                val variantKey = "$normalizedLanguage|${format.channelCount}|${if (mpdIsAd) "ad" else "main"}"
                val variantOrdinal = languageVariantCounts[variantKey] ?: 0
                languageVariantCounts[variantKey] = variantOrdinal + 1

                val metadata = resolveAudioOptionMetadata(
                    normalizedLanguage = normalizedLanguage,
                    rawLabel = rawLabel,
                    variantOrdinal = variantOrdinal,
                    mpdIsAd = mpdIsAd
                )
                val isAd = AudioTrackLabeler.isAudioDescriptionTrack(format, metadata?.displayName)
                val familyKind = when {
                    isAd -> "ad"
                    else -> AudioTrackLabeler.resolveAudioFamilyKind(format, metadata, rawLabel)
                }
                val label = AudioTrackLabeler.resolveAudioMenuLabel(
                    normalizedLanguage = normalizedLanguage,
                    metadata = metadata,
                    rawLabel = rawLabel,
                    fallbackLanguage = normalizedLanguage,
                    channelCount = format.channelCount,
                    familyKind = familyKind
                )
                resolved += TrackOption(
                    group = group,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = label,
                    familyKind = familyKind,
                    isSelected = group.isTrackSelected(trackIndex),
                    isAudioDescription = familyKind == "ad",
                    bitrate = format.bitrate,
                    language = normalizedLanguage
                )
                resolutionEntries += "group=$groupIndex track=$trackIndex raw=${rawLabel.ifBlank { "-" }} lang=${format.language.orEmpty()} role=${format.roleFlags} channels=${format.channelCount} metadata=${metadata?.displayName ?: "-"} type=${metadata?.type ?: "-"} index=${metadata?.index ?: "-"} family=$familyKind label=$label selected=${group.isTrackSelected(trackIndex)}"
            }
        }

        val resolutionSignature = resolutionEntries.joinToString(" || ")
        if (resolutionSignature != lastLoggedAudioResolutionSignature) {
            lastLoggedAudioResolutionSignature = resolutionSignature
            Log.i(TAG, "Audio track resolution: ${resolutionEntries.joinToString(" | ")}")
        }

        val bestByLabel = linkedMapOf<String, TrackOption>()
        for (option in resolved) {
            val key = option.label.replace("\\s+".toRegex(), " ").trim().lowercase()
            val existing = bestByLabel[key]
            val better = existing == null ||
                (option.isSelected && !existing.isSelected) ||
                (!existing.isSelected && option.bitrate > existing.bitrate)
            if (better) bestByLabel[key] = option
        }

        val sorted = bestByLabel.values.sortedWith(
            compareBy<TrackOption> { it.language }
                .thenBy { if (it.isAudioDescription) 1 else 0 }
                .thenBy { it.label.lowercase() }
        )

        Log.i(TAG, "Audio menu options: ${
            sorted.joinToString(" | ") {
                "label=${it.label}, selected=${it.isSelected}, group=${it.groupIndex}, track=${it.trackIndex}"
            }
        }")
        return sorted
    }

    /**
     * Finds the best [AudioTrack] metadata entry for a live ExoPlayer track identified by
     * [normalizedLanguage] + [variantOrdinal] within its AD/non-AD family.
     */
    fun resolveAudioOptionMetadata(
        normalizedLanguage: String,
        rawLabel: String,
        variantOrdinal: Int,
        mpdIsAd: Boolean = false
    ): AudioTrack? {
        if (availableAudioTracks.isEmpty()) return null

        val directMatch = availableAudioTracks.firstOrNull {
            it.displayName.equals(rawLabel, ignoreCase = true)
        }
        if (directMatch != null) return directMatch

        val languageMatches = availableAudioTracks.filter {
            AudioTrackLabeler.normalizeAudioGroupLanguage(it.languageCode) == normalizedLanguage
        }
        if (languageMatches.isEmpty()) return null

        // If the MPD authoritatively identifies this track's AD status, filter metadata
        // to only match the correct family (descriptive vs. non-descriptive).
        val typedByMpd = if (mpdIsAd) {
            languageMatches.filter { it.type.equals("descriptive", ignoreCase = true) }
                .ifEmpty { languageMatches }
        } else {
            languageMatches.filter { !it.type.equals("descriptive", ignoreCase = true) }
                .ifEmpty { languageMatches }
        }

        val requestedFamily = AudioTrackLabeler.audioFamilyKind(null, rawLabel)
        val typedMatches = if (requestedFamily == "main") {
            typedByMpd
        } else {
            typedByMpd.filter { AudioTrackLabeler.audioFamilyKind(it, it.displayName) == requestedFamily }
        }
        val candidates = typedMatches.ifEmpty { typedByMpd }

        val orderedByFamily = candidates
            .sortedWith(
                compareBy<AudioTrack> {
                    AudioTrackLabeler.audioFamilyRank(AudioTrackLabeler.audioFamilyKind(it, it.displayName))
                }.thenBy { it.displayName.length }
            )
            .distinctBy { AudioTrackLabeler.audioFamilyKind(it, it.displayName) }

        return orderedByFamily.getOrNull(variantOrdinal)
            ?: orderedByFamily.firstOrNull()
            ?: candidates.minWithOrNull(compareBy { it.displayName.length })
    }

    fun metadataFamiliesForLanguage(normalizedLanguage: String): List<AudioMetadataFamily> {
        return availableAudioTracks
            .filter { AudioTrackLabeler.normalizeAudioGroupLanguage(it.languageCode) == normalizedLanguage }
            .map {
                val familyKind = AudioTrackLabeler.audioFamilyKind(it, it.displayName)
                AudioMetadataFamily(
                    familyKind = familyKind,
                    label = it.displayName.replace("\\s+".toRegex(), " ").trim(),
                    isAudioDescription = familyKind == "ad"
                )
            }
            .distinctBy { it.familyKind }
            .sortedBy { AudioTrackLabeler.audioFamilyRank(it.familyKind) }
    }

    fun resolveAudioMetadataName(format: Format, guessedAd: Boolean): String? {
        if (availableAudioTracks.isEmpty()) return null
        val normalizedLanguage = AudioTrackLabeler.normalizeLanguageCode(format.language)
        val directLabel = format.label?.trim().orEmpty()
        if (directLabel.isNotBlank()) {
            availableAudioTracks.firstOrNull { it.displayName.equals(directLabel, ignoreCase = true) }
                ?.let { return it.displayName }
        }

        val languageMatches = availableAudioTracks.filter {
            AudioTrackLabeler.normalizeLanguageCode(it.languageCode) == normalizedLanguage
        }
        if (languageMatches.size == 1) return languageMatches.first().displayName

        if (languageMatches.isNotEmpty()) {
            val preferredMatches = if (guessedAd) {
                languageMatches.filter {
                    it.isAudioDescription() || it.type.equals("descriptive", ignoreCase = true)
                }
            } else {
                languageMatches.filter {
                    !it.isAudioDescription() && it.type.equals("dialog", ignoreCase = true)
                }.ifEmpty {
                    languageMatches.filterNot { it.isAudioDescription() }
                }
            }
            if (preferredMatches.size == 1) return preferredMatches.first().displayName
            if (directLabel.isNotBlank()) {
                preferredMatches.firstOrNull {
                    it.displayName.contains(directLabel, ignoreCase = true)
                }?.let { return it.displayName }
            }
        }

        if (directLabel.isNotBlank()) {
            availableAudioTracks.firstOrNull {
                it.displayName.contains(directLabel, ignoreCase = true)
            }?.let { return it.displayName }
        }
        return null
    }

    fun fallbackAudioLabel(candidate: AudioLiveCandidate, normalizedLanguage: String): String {
        val cleanedLive = candidate.rawLabel.replace("\\s+".toRegex(), " ").trim()
        val base = cleanedLive.ifBlank { AudioTrackLabeler.displayLanguage(normalizedLanguage) }
        return AudioTrackLabeler.appendChannelLayout(base, candidate.channelCount)
    }
}
