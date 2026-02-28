package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scriptgod.fireos.avod.R
import android.widget.LinearLayout
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.model.ContentRail
import com.scriptgod.fireos.avod.api.AmazonApiService.LibraryFilter
import com.scriptgod.fireos.avod.api.AmazonApiService.LibrarySort
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.model.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var etSearch: DpadEditText
    private lateinit var btnSearch: Button
    private lateinit var btnHome: Button
    private lateinit var btnFreevee: Button
    private lateinit var btnWatchlist: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnAbout: Button

    // Category buttons — two independent groups
    private lateinit var categoryFilterRow: LinearLayout
    private lateinit var sourceFilterGroup: LinearLayout
    private lateinit var btnCatAll: Button
    private lateinit var btnCatPrime: Button
    private lateinit var btnTypeAll: Button
    private lateinit var btnCatMovies: Button
    private lateinit var btnCatSeries: Button

    // Phase 10 library buttons
    private lateinit var libraryFilterRow: LinearLayout
    private lateinit var btnLibAll: Button
    private lateinit var btnLibMovies: Button
    private lateinit var btnLibShows: Button
    private lateinit var btnLibSort: Button

    private lateinit var authService: AmazonAuthService
    private lateinit var apiService: AmazonApiService
    private lateinit var adapter: ContentAdapter
    private lateinit var railsAdapter: RailsAdapter

    // Rails mode state
    private var isRailsMode: Boolean = false
    private var homeNextPageParams: String = ""
    private var homePageLoading: Boolean = false
    private var unfilteredRails: List<ContentRail> = emptyList()

    // Two independent filter dimensions
    private var sourceFilter: String = "all"   // "all" or "prime"
    private var typeFilter: String = "all"     // "all", "movies", or "series"
    private var currentNavPage: String = "home"
    private var watchlistAsins: MutableSet<String> = mutableSetOf()
    private var watchlistProgress: Map<String, Pair<Long, Long>> = emptyMap()

    // Phase 10: Library state
    private var libraryFilter: LibraryFilter = LibraryFilter.ALL
    private var librarySort: LibrarySort = LibrarySort.DATE_ADDED
    private var libraryNextIndex: Int = 0
    private var libraryLoading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        etSearch = findViewById(R.id.et_search)
        btnSearch = findViewById(R.id.btn_search)
        btnHome = findViewById(R.id.btn_home)
        btnFreevee = findViewById(R.id.btn_freevee)
        btnWatchlist = findViewById(R.id.btn_watchlist)
        btnLibrary = findViewById(R.id.btn_library)
        btnAbout = findViewById(R.id.btn_about)

        categoryFilterRow = findViewById(R.id.category_filter_row)
        sourceFilterGroup = findViewById(R.id.source_filter_group)
        btnCatAll = findViewById(R.id.btn_cat_all)
        btnCatPrime = findViewById(R.id.btn_cat_prime)
        btnTypeAll = findViewById(R.id.btn_type_all)
        btnCatMovies = findViewById(R.id.btn_cat_movies)
        btnCatSeries = findViewById(R.id.btn_cat_series)

        libraryFilterRow = findViewById(R.id.library_filter_row)
        btnLibAll = findViewById(R.id.btn_lib_all)
        btnLibMovies = findViewById(R.id.btn_lib_movies)
        btnLibShows = findViewById(R.id.btn_lib_shows)
        btnLibSort = findViewById(R.id.btn_lib_sort)

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
        authService = AmazonAuthService(tokenFile)
        apiService = AmazonApiService(authService)

        adapter = ContentAdapter(
            onItemClick = { item -> onItemSelected(item) },
            onMenuKey = { item -> showItemMenu(item) }
        )
        railsAdapter = RailsAdapter(
            onItemClick = { item -> onItemSelected(item) },
            onMenuKey = { item -> showItemMenu(item) }
        )
        val gridLayoutManager = GridLayoutManager(this, 5)
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter

        // On Fire TV: EditText gets D-pad focus (highlight) but keyboard only
        // shows when user presses DPAD_CENTER (click). stateHidden in manifest
        // prevents keyboard on initial activity focus.
        // Show keyboard on DPAD_CENTER click
        etSearch.setOnClickListener { v ->
            Log.i(TAG, "Search field clicked — showing keyboard")
            v.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(v, InputMethodManager.SHOW_FORCED)
        }

        // Handle back press while keyboard is showing (onKeyPreIme intercepts before IME)
        etSearch.onBackPressedWhileFocused = {
            Log.i(TAG, "Back pressed while search focused — hiding keyboard")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
            etSearch.clearFocus()
            recyclerView.requestFocus()
        }

        // IME "Search" action → perform search and dismiss keyboard
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dismissKeyboardAndSearch()
                true
            } else false
        }

        // Hardware Enter key → perform search and dismiss keyboard
        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                dismissKeyboardAndSearch()
                true
            } else false
        }

        // Search button click
        btnSearch.setOnClickListener { dismissKeyboardAndSearch() }

        // Navigation buttons
        btnHome.setOnClickListener { loadNav("home") }
        btnFreevee.setOnClickListener { loadNav("freevee") }
        btnWatchlist.setOnClickListener { loadNav("watchlist") }
        btnLibrary.setOnClickListener { loadNav("library") }
        btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        // Source filter buttons (All vs Prime)
        btnCatAll.setOnClickListener { setSourceFilter("all") }
        btnCatPrime.setOnClickListener { setSourceFilter("prime") }
        // Type filter buttons (All Types vs Movies vs Series)
        btnTypeAll.setOnClickListener { setTypeFilter("all") }
        btnCatMovies.setOnClickListener { setTypeFilter("movies") }
        btnCatSeries.setOnClickListener { setTypeFilter("series") }

        // Library filter buttons
        btnLibAll.setOnClickListener { setLibraryFilter(LibraryFilter.ALL) }
        btnLibMovies.setOnClickListener { setLibraryFilter(LibraryFilter.MOVIES) }
        btnLibShows.setOnClickListener { setLibraryFilter(LibraryFilter.TV_SHOWS) }
        btnLibSort.setOnClickListener { cycleLibrarySort() }

        // Infinite scroll for library, watchlist, and rails pagination
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager ?: return
                val totalItems = lm.itemCount
                val lastVisible = when (lm) {
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                when {
                    currentNavPage == "library" && lastVisible >= totalItems - 10 -> {
                        if (!libraryLoading && libraryNextIndex > 0) loadLibraryNextPage()
                    }
                    isRailsMode && lastVisible >= totalItems - 3 -> {
                        if (!homePageLoading && homeNextPageParams.isNotEmpty()) loadHomeRailsNextPage()
                    }
                }
            }
        })

        // Give initial focus to recycler, not the search field (prevents keyboard on startup)
        etSearch.clearFocus()
        recyclerView.requestFocus()

        // Hide filter rows on initial home load (rails are server-curated)
        updateFilterRowVisibility()

        // Detect territory, fetch watchlist ASINs, then load home rails
        showLoading()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                apiService.detectTerritory()
                val (asins, progress) = apiService.getWatchlistData()
                watchlistAsins = asins.toMutableSet()
                watchlistProgress = progress
                Log.i(TAG, "Watchlist loaded: ${watchlistAsins.size} items, ${watchlistProgress.size} with progress")
            }
            loadHomeRails()
        }
    }

    private fun dismissKeyboardAndSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
        recyclerView.requestFocus()
        performSearch()
    }

    // --- Filter selection (two independent dimensions) ---

    private fun setSourceFilter(source: String) {
        sourceFilter = source
        updateFilterHighlights()
        reloadFiltered()
    }

    private fun setTypeFilter(type: String) {
        typeFilter = type
        updateFilterHighlights()
        if (isRailsMode) {
            applyRailsTypeFilter()
        } else {
            reloadFiltered()
        }
    }

    private fun updateFilterHighlights() {
        val activeColor = Color.parseColor("#00A8E0")
        val inactiveColor = Color.parseColor("#555555")
        val activeText = Color.BLACK
        val inactiveText = Color.WHITE

        // Source group
        listOf(btnCatAll to "all", btnCatPrime to "prime").forEach { (btn, value) ->
            val active = value == sourceFilter
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
            btn.setTextColor(if (active) activeText else inactiveText)
        }
        // Type group
        listOf(btnTypeAll to "all", btnCatMovies to "movies", btnCatSeries to "series").forEach { (btn, value) ->
            val active = value == typeFilter
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
            btn.setTextColor(if (active) activeText else inactiveText)
        }
    }

    private fun reloadFiltered() {
        val query = etSearch.text.toString().trim()
        loadFilteredContent(query)
    }

    private fun loadFilteredContent(query: String = "") {
        showLoading()
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val raw = if (query.isNotEmpty()) apiService.getSearchPage(query)
                              else when (currentNavPage) {
                                  "watchlist" -> apiService.getAllWatchlistItems()
                                  else -> apiService.getHomePage()
                              }
                    applyFilters(raw)
                }
                showItems(items)
            } catch (e: Exception) {
                Log.i(TAG, "Error loading filtered content source=$sourceFilter type=$typeFilter", e)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun applyFilters(items: List<ContentItem>): List<ContentItem> {
        var result = items
        if (sourceFilter == "prime") {
            result = result.filter { it.isPrime }
        }
        when (typeFilter) {
            "movies" -> result = result.filter { AmazonApiService.isMovieContentType(it.contentType) }
            "series" -> result = result.filter { AmazonApiService.isSeriesContentType(it.contentType) }
        }
        return result
    }

    // --- Search ---

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        Log.i(TAG, "performSearch query='$query'")
        if (query.isEmpty() && currentNavPage == "home") {
            // Search cleared — reload rails
            loadHomeRails()
        } else {
            if (query.isNotEmpty()) switchToGridMode()
            loadFilteredContent(query)
        }
    }

    // --- Nav pages (Home / Watchlist / Library) ---

    private fun loadNav(page: String) {
        currentNavPage = page
        sourceFilter = "all"
        typeFilter = "all"
        updateFilterHighlights()
        updateNavButtonHighlight()
        updateFilterRowVisibility()

        if (page == "library") {
            switchToGridMode()
            libraryFilter = LibraryFilter.ALL
            librarySort = LibrarySort.DATE_ADDED
            updateLibraryFilterHighlight()
            updateLibrarySortLabel()
            loadLibraryInitial()
            return
        }

        if (page == "watchlist") {
            switchToGridMode()
            loadWatchlistInitial()
            return
        }

        if (page == "home") {
            loadHomeRails()
            return
        }

        switchToGridMode()

        // Freevee — no filters
        showLoading()
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    apiService.getFreeveePage()
                }
                showItems(items)
            } catch (e: Exception) {
                Log.i(TAG, "Error loading page $page", e)
                showError("Error: ${e.message}")
            }
        }
    }

    // --- Nav button highlight ---

    private fun updateNavButtonHighlight() {
        val activeColor = Color.parseColor("#00A8E0")
        val inactiveColor = Color.parseColor("#333333")
        val activeText = Color.BLACK
        val inactiveText = Color.WHITE

        listOf(
            btnHome to "home",
            btnFreevee to "freevee",
            btnWatchlist to "watchlist",
            btnLibrary to "library"
        ).forEach { (btn, page) ->
            val active = page == currentNavPage
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
            btn.setTextColor(if (active) activeText else inactiveText)
        }
    }

    private fun updateFilterRowVisibility() {
        when (currentNavPage) {
            "library" -> {
                categoryFilterRow.visibility = View.GONE
                libraryFilterRow.visibility = View.VISIBLE
                setNavFocusTarget(R.id.btn_lib_all)
            }
            "home" -> {
                // Show type filters (Movies/Series) but hide source group (All/Prime)
                categoryFilterRow.visibility = View.VISIBLE
                sourceFilterGroup.visibility = View.GONE
                libraryFilterRow.visibility = View.GONE
                setNavFocusTarget(R.id.btn_type_all)
            }
            "watchlist" -> {
                categoryFilterRow.visibility = View.VISIBLE
                sourceFilterGroup.visibility = View.VISIBLE
                libraryFilterRow.visibility = View.GONE
                setNavFocusTarget(R.id.btn_cat_all)
            }
            else -> {
                categoryFilterRow.visibility = View.GONE
                libraryFilterRow.visibility = View.GONE
                setNavFocusTarget(R.id.recycler_view)
            }
        }
    }

    private fun setNavFocusTarget(targetId: Int) {
        btnHome.nextFocusDownId = targetId
        btnFreevee.nextFocusDownId = targetId
        btnWatchlist.nextFocusDownId = targetId
        btnLibrary.nextFocusDownId = targetId
        // Also update recyclerView's upward focus target
        recyclerView.nextFocusUpId = targetId
    }

    // --- Watchlist pagination ---

    private fun loadWatchlistInitial() {
        showLoading()
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    apiService.getAllWatchlistItems()
                }
                showItems(items)
                if (items.isEmpty()) {
                    showError("Your watchlist is empty.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading watchlist", e)
                showError("Error: ${e.message}")
            }
        }
    }

    // --- Library (Phase 10) ---

    private fun setLibraryFilter(filter: LibraryFilter) {
        libraryFilter = filter
        updateLibraryFilterHighlight()
        loadLibraryInitial()
    }

    private fun cycleLibrarySort() {
        librarySort = when (librarySort) {
            LibrarySort.DATE_ADDED -> LibrarySort.TITLE_AZ
            LibrarySort.TITLE_AZ -> LibrarySort.TITLE_ZA
            LibrarySort.TITLE_ZA -> LibrarySort.DATE_ADDED
        }
        updateLibrarySortLabel()
        loadLibraryInitial()
    }

    private fun updateLibraryFilterHighlight() {
        val activeColor = Color.parseColor("#00A8E0")
        val inactiveColor = Color.parseColor("#555555")
        val activeText = Color.BLACK
        val inactiveText = Color.WHITE

        listOf(
            btnLibAll to LibraryFilter.ALL,
            btnLibMovies to LibraryFilter.MOVIES,
            btnLibShows to LibraryFilter.TV_SHOWS
        ).forEach { (btn, f) ->
            val active = f == libraryFilter
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
            btn.setTextColor(if (active) activeText else inactiveText)
        }
    }

    private fun updateLibrarySortLabel() {
        btnLibSort.text = when (librarySort) {
            LibrarySort.DATE_ADDED -> "↕ Recent"
            LibrarySort.TITLE_AZ -> "↕ A → Z"
            LibrarySort.TITLE_ZA -> "↕ Z → A"
        }
    }

    private fun loadLibraryInitial() {
        libraryNextIndex = 0
        showLoading()
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    apiService.getLibraryPage(0, libraryFilter, librarySort)
                }
                libraryNextIndex = if (items.size >= 20) items.size else 0
                Log.i(TAG, "Library loaded: ${items.size} items, nextIndex=$libraryNextIndex")
                if (items.isEmpty()) {
                    showError("Your library is empty.\nRent or buy titles to see them here.")
                } else {
                    showItems(items)
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error loading library", e)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun loadLibraryNextPage() {
        if (libraryLoading || libraryNextIndex <= 0) return
        libraryLoading = true
        Log.i(TAG, "Loading library next page at index $libraryNextIndex")
        lifecycleScope.launch {
            try {
                val newItems = withContext(Dispatchers.IO) {
                    apiService.getLibraryPage(libraryNextIndex, libraryFilter, librarySort)
                }
                if (newItems.isNotEmpty()) {
                    libraryNextIndex += newItems.size
                    val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
                    val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
                    val markedItems = newItems.map { it.copy(
                        isInWatchlist = watchlistAsins.contains(it.asin),
                        watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs
                    ) }
                    val combined = adapter.currentList + markedItems
                    adapter.submitList(combined)
                    Log.i(TAG, "Library appended ${newItems.size} items, total=${combined.size}")
                } else {
                    libraryNextIndex = 0 // No more pages
                    Log.i(TAG, "Library pagination complete — no more items")
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error loading library next page", e)
            } finally {
                libraryLoading = false
            }
        }
    }

    // --- Item selection ---

    private fun onItemSelected(item: ContentItem) {
        Log.i(TAG, "Selected: ${item.asin} — ${item.title} (type=${item.contentType})")
        if (AmazonApiService.isSeriesContentType(item.contentType)) {
            // Series/Season → drill down to seasons/episodes in BrowseActivity
            val intent = Intent(this, BrowseActivity::class.java).apply {
                putExtra(BrowseActivity.EXTRA_ASIN, item.asin)
                putExtra(BrowseActivity.EXTRA_TITLE, item.title)
                putExtra(BrowseActivity.EXTRA_CONTENT_TYPE, item.contentType)
                putExtra(BrowseActivity.EXTRA_IMAGE_URL, item.imageUrl)
                putStringArrayListExtra(BrowseActivity.EXTRA_WATCHLIST_ASINS, ArrayList(watchlistAsins))
            }
            startActivity(intent)
        } else {
            // Movie/Episode/Feature → play directly
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ASIN, item.asin)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, item.contentType)
            }
            startActivity(intent)
        }
    }

    // --- Watchlist context menu (MENU key) ---

    private fun showItemMenu(item: ContentItem) {
        val isIn = watchlistAsins.contains(item.asin)
        val label = if (isIn) "Remove from Watchlist" else "Add to Watchlist"
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf(label)) { _, _ -> toggleWatchlist(item) }
            .show()
    }

    private fun toggleWatchlist(item: ContentItem) {
        val isCurrentlyIn = watchlistAsins.contains(item.asin)
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (isCurrentlyIn) apiService.removeFromWatchlist(item.asin)
                else apiService.addToWatchlist(item.asin)
            }
            if (success) {
                if (isCurrentlyIn) watchlistAsins.remove(item.asin)
                else watchlistAsins.add(item.asin)

                val result = if (isCurrentlyIn) "Removed from" else "Added to"
                Toast.makeText(this@MainActivity, "$result watchlist", Toast.LENGTH_SHORT).show()

                // Update flat grid adapter
                val updatedList = adapter.currentList.map { ci ->
                    if (ci.asin == item.asin) ci.copy(isInWatchlist = !isCurrentlyIn) else ci
                }
                adapter.submitList(updatedList)

                // Update rails adapter
                if (unfilteredRails.isNotEmpty()) {
                    unfilteredRails = unfilteredRails.map { rail ->
                        rail.copy(items = rail.items.map { ci ->
                            if (ci.asin == item.asin) ci.copy(isInWatchlist = !isCurrentlyIn) else ci
                        })
                    }
                    railsAdapter.submitList(applyTypeFilterToRails(unfilteredRails))
                }
            } else {
                Toast.makeText(this@MainActivity, "Watchlist update failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Rails mode ---

    private fun switchToRailsMode() {
        if (isRailsMode) return
        isRailsMode = true
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerView.adapter = railsAdapter
    }

    private fun switchToGridMode() {
        if (!isRailsMode) return
        isRailsMode = false
        recyclerView.layoutManager = GridLayoutManager(this, 5)
        recyclerView.adapter = adapter
    }

    private fun loadHomeRails() {
        switchToRailsMode()
        showLoading()
        homeNextPageParams = ""
        lifecycleScope.launch {
            try {
                val (rails, nextParams) = withContext(Dispatchers.IO) {
                    apiService.getHomePageRails()
                }
                homeNextPageParams = nextParams
                showRails(rails)
            } catch (e: Exception) {
                Log.w(TAG, "Error loading home rails", e)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun loadHomeRailsNextPage() {
        if (homePageLoading || homeNextPageParams.isEmpty()) return
        homePageLoading = true
        Log.i(TAG, "Loading next page of rails")
        lifecycleScope.launch {
            try {
                val (newRails, nextParams) = withContext(Dispatchers.IO) {
                    apiService.getHomePageRails(homeNextPageParams)
                }
                homeNextPageParams = nextParams
                if (newRails.isNotEmpty()) {
                    val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
                    val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
                    val markedRails = newRails.map { rail ->
                        rail.copy(items = rail.items.map { item ->
                            val localProgress = resumeMap[item.asin]
                            val serverProgress = watchlistProgress[item.asin]
                            val progressMs = localProgress
                                ?: serverProgress?.first
                                ?: item.watchProgressMs
                            val runtimeMs = if (serverProgress != null && item.runtimeMs == 0L)
                                serverProgress.second else item.runtimeMs
                            item.copy(
                                isInWatchlist = watchlistAsins.contains(item.asin),
                                watchProgressMs = progressMs,
                                runtimeMs = runtimeMs
                            )
                        })
                    }
                    unfilteredRails = unfilteredRails + markedRails
                    val filtered = applyTypeFilterToRails(unfilteredRails)
                    railsAdapter.submitList(filtered)
                    Log.i(TAG, "Appended ${newRails.size} rails, total unfiltered=${unfilteredRails.size}")
                } else {
                    homeNextPageParams = ""
                    Log.i(TAG, "Rails pagination complete — no more rails")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading next rails page", e)
            } finally {
                homePageLoading = false
            }
        }
    }

    private fun showRails(rails: List<ContentRail>) {
        progressBar.visibility = View.GONE
        if (rails.isEmpty()) {
            tvError.text = "No content found"
            tvError.visibility = View.VISIBLE
        } else {
            tvError.visibility = View.GONE
            val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
            val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
            // Merge watchlist flags, server-side watch progress, and local resume into rail items
            val markedRails = rails.map { rail ->
                rail.copy(items = rail.items.map { item ->
                    val localProgress = resumeMap[item.asin]
                    val serverProgress = watchlistProgress[item.asin]
                    val progressMs = localProgress
                        ?: serverProgress?.first
                        ?: item.watchProgressMs
                    val runtimeMs = if (serverProgress != null && item.runtimeMs == 0L)
                        serverProgress.second else item.runtimeMs
                    item.copy(
                        isInWatchlist = watchlistAsins.contains(item.asin),
                        watchProgressMs = progressMs,
                        runtimeMs = runtimeMs
                    )
                })
            }
            unfilteredRails = markedRails
            val filtered = applyTypeFilterToRails(markedRails)
            railsAdapter.submitList(filtered)
            recyclerView.post {
                val firstChild = recyclerView.getChildAt(0)
                if (firstChild != null) firstChild.requestFocus()
                else recyclerView.requestFocus()
            }
        }
    }

    private fun applyRailsTypeFilter() {
        val filtered = applyTypeFilterToRails(unfilteredRails)
        if (filtered.isEmpty()) {
            tvError.text = "No content found"
            tvError.visibility = View.VISIBLE
        } else {
            tvError.visibility = View.GONE
        }
        railsAdapter.submitList(filtered)
    }

    private fun applyTypeFilterToRails(rails: List<ContentRail>): List<ContentRail> {
        if (typeFilter == "all") return rails
        return rails.mapNotNull { rail ->
            val filteredItems = rail.items.filter { item ->
                when (typeFilter) {
                    "movies" -> AmazonApiService.isMovieContentType(item.contentType)
                    "series" -> AmazonApiService.isSeriesContentType(item.contentType)
                    else -> true
                }
            }
            if (filteredItems.isEmpty()) null
            else rail.copy(items = filteredItems)
        }
    }

    // --- View state helpers ---

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        if (isRailsMode) railsAdapter.submitList(emptyList())
        else adapter.submitList(emptyList())
    }

    private fun showItems(items: List<ContentItem>) {
        progressBar.visibility = View.GONE
        if (items.isEmpty()) {
            tvError.text = "No content found"
            tvError.visibility = View.VISIBLE
        } else {
            tvError.visibility = View.GONE
            // Merge watchlist flags and watch progress into items
            val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
            val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }
            val markedItems = items
                .map { item ->
                    val localProgress = resumeMap[item.asin]
                    val serverProgress = watchlistProgress[item.asin]
                    val progressMs = localProgress
                        ?: serverProgress?.first
                        ?: item.watchProgressMs
                    val runtimeMs = if (serverProgress != null && item.runtimeMs == 0L)
                        serverProgress.second else item.runtimeMs
                    item.copy(
                        isInWatchlist = watchlistAsins.contains(item.asin),
                        watchProgressMs = progressMs,
                        runtimeMs = runtimeMs
                    )
                }
            adapter.submitList(markedItems)
            // After items are submitted, request focus on first grid item for D-pad navigation
            recyclerView.post {
                val firstChild = recyclerView.getChildAt(0)
                if (firstChild != null) {
                    firstChild.requestFocus()
                } else {
                    recyclerView.requestFocus()
                }
            }
        }
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val item = focusedContentItem()
            if (item != null) {
                showItemMenu(item)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun focusedContentItem(): ContentItem? {
        // Walk up from the focused descendant, return the first view tagged with a ContentItem.
        // Works for both flat grid (direct child) and nested rails (inner RecyclerView child).
        var view: View? = recyclerView.findFocus() ?: return null
        while (view != null && view !== recyclerView) {
            val item = view.tag as? ContentItem
            if (item != null) return item
            view = view.parent as? View
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        val resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)
        val resumeMap = resumePrefs.all.mapValues { (it.value as? Long) ?: 0L }

        if (isRailsMode) {
            // Refresh watch progress within unfiltered cache and re-filter
            if (unfilteredRails.isNotEmpty()) {
                unfilteredRails = unfilteredRails.map { rail ->
                    rail.copy(items = rail.items.map { it.copy(
                        watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs
                    ) })
                }
                val filtered = applyTypeFilterToRails(unfilteredRails)
                railsAdapter.submitList(filtered)
            }
        } else {
            // Refresh watch progress on flat grid cards
            val currentList = adapter.currentList
            if (currentList.isNotEmpty()) {
                val updated = currentList.map { it.copy(watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs) }
                adapter.submitList(updated)
            }
        }

        // Re-focus when returning from another activity
        recyclerView.post {
            val firstChild = recyclerView.getChildAt(0)
            if (firstChild != null) firstChild.requestFocus()
            else recyclerView.requestFocus()
        }
    }
}
