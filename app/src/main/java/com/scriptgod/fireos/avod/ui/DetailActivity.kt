package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.model.DetailInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DetailActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_WATCHLIST_ASINS = "extra_watchlist_asins"
    }

    private lateinit var layoutContent: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var ivHero: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var tvImdb: TextView
    private lateinit var tvGenres: TextView
    private lateinit var tvSynopsis: TextView
    private lateinit var tvDirectors: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnBrowse: Button
    private lateinit var btnSeasons: Button
    private lateinit var btnWatchlist: Button

    private lateinit var apiService: AmazonApiService
    private var watchlistAsins: MutableSet<String> = mutableSetOf()
    private var currentAsin: String = ""
    private var currentContentType: String = ""
    private var fallbackImageUrl: String = ""
    private var detailInfo: DetailInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        layoutContent = findViewById(R.id.layout_content)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        ivHero = findViewById(R.id.iv_hero)
        ivPoster = findViewById(R.id.iv_poster)
        tvTitle = findViewById(R.id.tv_title)
        tvMetadata = findViewById(R.id.tv_metadata)
        tvImdb = findViewById(R.id.tv_imdb)
        tvGenres = findViewById(R.id.tv_genres)
        tvSynopsis = findViewById(R.id.tv_synopsis)
        tvDirectors = findViewById(R.id.tv_directors)
        btnPlay = findViewById(R.id.btn_play)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnBrowse = findViewById(R.id.btn_browse)
        btnSeasons = findViewById(R.id.btn_seasons)
        btnWatchlist = findViewById(R.id.btn_watchlist)

        val tokenFile = LoginActivity.findTokenFile(this) ?: run { finish(); return }
        apiService = AmazonApiService(AmazonAuthService(tokenFile))

        currentAsin = intent.getStringExtra(EXTRA_ASIN) ?: run { finish(); return }
        currentContentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ""
        fallbackImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""
        watchlistAsins = (intent.getStringArrayListExtra(EXTRA_WATCHLIST_ASINS) ?: ArrayList()).toMutableSet()

        tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        loadDetail()
    }

    private fun loadDetail() {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE
        tvError.visibility = View.GONE

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    apiService.detectTerritory()
                    apiService.getDetailInfo(currentAsin)
                } catch (e: Exception) {
                    Log.w(TAG, "loadDetail failed", e)
                    null
                }
            }

            progressBar.visibility = View.GONE

            if (info == null) {
                tvError.text = "Could not load details"
                tvError.visibility = View.VISIBLE
                return@launch
            }

            detailInfo = info
            bindDetail(info)
        }
    }

    private fun bindDetail(info: DetailInfo) {
        // Hero image
        val heroUrl = info.heroImageUrl.ifEmpty { fallbackImageUrl }
        if (heroUrl.isNotEmpty()) {
            ivHero.load(heroUrl) { crossfade(true) }
        }

        // Poster image
        val posterUrl = info.posterImageUrl.ifEmpty { fallbackImageUrl }
        if (posterUrl.isNotEmpty()) {
            ivPoster.load(posterUrl) { crossfade(true) }
        } else {
            ivPoster.setBackgroundColor(0xFF222222.toInt())
        }

        // Title
        tvTitle.text = if (info.showTitle.isNotEmpty()) "${info.showTitle}: ${info.title}"
                       else info.title

        // Metadata row: year · runtime · age rating · quality
        val meta = buildString {
            if (info.year > 0) append(info.year)
            if (info.runtimeSeconds > 0) {
                if (isNotEmpty()) append("  ·  ")
                val h = info.runtimeSeconds / 3600
                val m = (info.runtimeSeconds % 3600) / 60
                append(if (h > 0) "${h}h ${m}min" else "${m}min")
            }
            if (info.ageRating.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(info.ageRating)
            }
            val qualityTags = buildList {
                if (info.isUhd) add("4K")
                if (info.isHdr) add("HDR")
                if (info.isDolby51) add("5.1")
            }
            if (qualityTags.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(qualityTags.joinToString(" "))
            }
        }
        tvMetadata.text = meta

        // IMDb
        if (info.imdbRating > 0f) {
            tvImdb.text = "IMDb  %.1f / 10".format(info.imdbRating)
            tvImdb.visibility = View.VISIBLE
        }

        // Genres
        if (info.genres.isNotEmpty()) {
            tvGenres.text = info.genres.joinToString("  ·  ")
            tvGenres.visibility = View.VISIBLE
        }

        // Synopsis
        if (info.synopsis.isNotEmpty()) {
            tvSynopsis.text = info.synopsis
            tvSynopsis.visibility = View.VISIBLE
        }

        // Directors
        if (info.directors.isNotEmpty()) {
            tvDirectors.text = "Director: " + info.directors.joinToString(", ")
            tvDirectors.visibility = View.VISIBLE
        }

        // Watchlist button
        updateWatchlistButton(info.isInWatchlist || watchlistAsins.contains(currentAsin))
        btnWatchlist.visibility = View.VISIBLE
        btnWatchlist.setOnClickListener { onWatchlistClicked() }

        // Play / Browse buttons based on content type
        val isSeries = AmazonApiService.isSeriesContentType(info.contentType)
        val isSeason = info.contentType.uppercase().contains("SEASON")
        if (isSeries) {
            if (isSeason) {
                // Season detail → Browse Episodes + All Seasons (via parent show ASIN)
                btnBrowse.text = "Browse Episodes"
                btnBrowse.visibility = View.VISIBLE
                btnBrowse.setOnClickListener { onBrowseClicked(info) }
                if (info.showAsin.isNotEmpty()) {
                    btnSeasons.visibility = View.VISIBLE
                    btnSeasons.setOnClickListener { onAllSeasonsClicked(info) }
                }
            } else {
                // Series overview → Browse Seasons
                btnBrowse.text = "Browse Seasons"
                btnBrowse.visibility = View.VISIBLE
                btnBrowse.setOnClickListener { onBrowseClicked(info) }
            }
        } else {
            // Movie / Feature → play
            btnPlay.visibility = View.VISIBLE
            btnPlay.setOnClickListener { onPlayClicked(info) }
        }

        // Trailer button (only if trailer is available and not a pure series overview)
        if (info.isTrailerAvailable && !isSeries) {
            btnTrailer.visibility = View.VISIBLE
            btnTrailer.setOnClickListener { onTrailerClicked(info) }
        }

        layoutContent.visibility = View.VISIBLE

        // Focus the primary action button
        layoutContent.post {
            when {
                btnPlay.visibility == View.VISIBLE -> btnPlay.requestFocus()
                btnBrowse.visibility == View.VISIBLE -> btnBrowse.requestFocus()
                else -> btnWatchlist.requestFocus()
            }
        }
    }

    private fun updateWatchlistButton(isIn: Boolean) {
        btnWatchlist.text = if (isIn) "★  Watchlist" else "☆  Watchlist"
    }

    private fun onPlayClicked(info: DetailInfo) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ASIN, info.asin)
            putExtra(PlayerActivity.EXTRA_TITLE, info.title)
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, info.contentType)
        }
        startActivity(intent)
    }

    private fun onTrailerClicked(info: DetailInfo) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ASIN, info.asin)
            putExtra(PlayerActivity.EXTRA_TITLE, "${info.title} — Trailer")
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, info.contentType)
            putExtra(PlayerActivity.EXTRA_MATERIAL_TYPE, "Trailer")
        }
        startActivity(intent)
    }

    private fun onBrowseClicked(info: DetailInfo) {
        val filter = if (info.contentType.uppercase().contains("SEASON")) "episodes" else "seasons"
        val intent = Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_ASIN, info.asin)
            putExtra(BrowseActivity.EXTRA_TITLE, info.title)
            putExtra(BrowseActivity.EXTRA_CONTENT_TYPE, info.contentType)
            putExtra(BrowseActivity.EXTRA_FILTER, filter)
            putExtra(BrowseActivity.EXTRA_IMAGE_URL, info.posterImageUrl.ifEmpty { fallbackImageUrl })
            putStringArrayListExtra(BrowseActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
        }
        startActivity(intent)
    }

    private fun onAllSeasonsClicked(info: DetailInfo) {
        val intent = Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_ASIN, info.showAsin)
            putExtra(BrowseActivity.EXTRA_TITLE, info.showTitle.ifEmpty { info.title })
            putExtra(BrowseActivity.EXTRA_CONTENT_TYPE, "SERIES")
            putExtra(BrowseActivity.EXTRA_FILTER, "seasons")
            putExtra(BrowseActivity.EXTRA_IMAGE_URL, info.posterImageUrl.ifEmpty { fallbackImageUrl })
            putStringArrayListExtra(BrowseActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
        }
        startActivity(intent)
    }

    private fun onWatchlistClicked() {
        val isIn = watchlistAsins.contains(currentAsin)
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (isIn) apiService.removeFromWatchlist(currentAsin)
                else apiService.addToWatchlist(currentAsin)
            }
            if (success) {
                if (isIn) watchlistAsins.remove(currentAsin)
                else watchlistAsins.add(currentAsin)
                updateWatchlistButton(watchlistAsins.contains(currentAsin))
                val msg = if (isIn) "Removed from watchlist" else "Added to watchlist"
                Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@DetailActivity, "Watchlist update failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
