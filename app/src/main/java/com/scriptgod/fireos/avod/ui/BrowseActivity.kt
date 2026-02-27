package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.model.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BrowseActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BrowseActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_FILTER = "extra_filter"  // "seasons" or "episodes"
        const val EXTRA_IMAGE_URL = "extra_image_url"  // fallback image for child items
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var apiService: AmazonApiService
    private lateinit var adapter: ContentAdapter
    private var parentImageUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        tvTitle = findViewById(R.id.tv_browse_title)
        tvSubtitle = findViewById(R.id.tv_browse_subtitle)

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        val authService = AmazonAuthService(tokenFile)
        apiService = AmazonApiService(authService)

        adapter = ContentAdapter(onItemClick = { item -> onItemSelected(item) })
        recyclerView.layoutManager = GridLayoutManager(this, 5)
        recyclerView.adapter = adapter

        val asin = intent.getStringExtra(EXTRA_ASIN) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ""
        parentImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""

        tvTitle.text = title

        // Determine filter: explicit extra overrides content type inference
        val filter = intent.getStringExtra(EXTRA_FILTER)
            ?: if (AmazonApiService.isSeriesContentType(contentType)) "seasons" else null

        when (filter) {
            "seasons" -> {
                tvSubtitle.text = "Select a season"
                tvSubtitle.visibility = View.VISIBLE
            }
            "episodes" -> {
                tvSubtitle.text = "Select an episode"
                tvSubtitle.visibility = View.VISIBLE
            }
        }
        loadDetails(asin, filterType = filter, fallbackImage = parentImageUrl)

        recyclerView.requestFocus()
    }

    private fun loadDetails(asin: String, filterType: String? = null, fallbackImage: String = "") {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    apiService.detectTerritory()
                    apiService.getDetailPage(asin)
                }
                progressBar.visibility = View.GONE
                Log.i(TAG, "Detail page for $asin returned ${items.size} items, types: ${items.map { it.contentType }.distinct()}")

                val filtered = when (filterType) {
                    "seasons" -> {
                        // Show seasons first; if none, show episodes directly
                        val seasons = items.filter { AmazonApiService.isSeriesContentType(it.contentType) }
                        if (seasons.isNotEmpty()) seasons
                        else {
                            // No seasons — might be episodes directly
                            val episodes = items.filter { AmazonApiService.isEpisodeContentType(it.contentType) }
                            if (episodes.isNotEmpty()) {
                                tvSubtitle.text = "Select an episode"
                                episodes
                            } else items // show all
                        }
                    }
                    "episodes" -> {
                        val episodes = items.filter { AmazonApiService.isEpisodeContentType(it.contentType) }
                        if (episodes.isNotEmpty()) episodes else items
                    }
                    else -> items
                }

                // Apply fallback image to items that have no image
                val withImages = if (fallbackImage.isNotEmpty()) {
                    filtered.map { item ->
                        if (item.imageUrl.isEmpty()) item.copy(imageUrl = fallbackImage) else item
                    }
                } else filtered

                if (withImages.isEmpty()) {
                    tvError.text = "No content found"
                    tvError.visibility = View.VISIBLE
                } else {
                    val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
                    val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
                    val withProgress = withImages.map { it.copy(watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs) }
                    adapter.submitList(withProgress)
                    // Focus first grid item for D-pad navigation
                    recyclerView.post {
                        val firstChild = recyclerView.getChildAt(0)
                        if (firstChild != null) firstChild.requestFocus()
                        else recyclerView.requestFocus()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading details for $asin", e)
                progressBar.visibility = View.GONE
                tvError.text = "Error: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh watch progress on cards when returning from player
        val currentList = adapter.currentList
        if (currentList.isNotEmpty()) {
            val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
            val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
            val updated = currentList.map { it.copy(watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs) }
            adapter.submitList(updated)
        }
        recyclerView.post {
            val firstChild = recyclerView.getChildAt(0)
            if (firstChild != null) firstChild.requestFocus()
            else recyclerView.requestFocus()
        }
    }

    private fun onItemSelected(item: ContentItem) {
        Log.i(TAG, "Selected: ${item.asin} — ${item.title} (type=${item.contentType})")
        when {
            // Season selected → show episodes for this season
            AmazonApiService.isSeriesContentType(item.contentType) -> {
                val intent = Intent(this, BrowseActivity::class.java).apply {
                    putExtra(EXTRA_ASIN, item.asin)
                    putExtra(EXTRA_TITLE, item.title)
                    putExtra(EXTRA_CONTENT_TYPE, item.contentType)
                    putExtra(EXTRA_FILTER, "episodes")
                    putExtra(EXTRA_IMAGE_URL, item.imageUrl.ifEmpty { parentImageUrl })
                }
                startActivity(intent)
            }
            // Episode/Movie/Feature → play
            else -> {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_ASIN, item.asin)
                    putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, item.contentType)
                }
                startActivity(intent)
            }
        }
    }
}
