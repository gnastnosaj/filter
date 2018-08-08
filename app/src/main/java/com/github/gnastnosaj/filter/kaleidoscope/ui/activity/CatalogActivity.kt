package com.github.gnastnosaj.filter.kaleidoscope.ui.activity

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.design.widget.TabLayout
import android.support.v4.view.ViewPager
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import br.com.mauker.materialsearchview.MaterialSearchView
import com.bilibili.socialize.share.core.shareparam.ShareParamText
import com.github.gnastnosaj.boilerplate.rxbus.RxHelper
import com.github.gnastnosaj.boilerplate.ui.activity.BaseActivity
import com.github.gnastnosaj.filter.dsl.core.Catalog
import com.github.gnastnosaj.filter.dsl.core.Category
import com.github.gnastnosaj.filter.dsl.groovy.api.Project
import com.github.gnastnosaj.filter.kaleidoscope.Kaleidoscope
import com.github.gnastnosaj.filter.kaleidoscope.R
import com.github.gnastnosaj.filter.kaleidoscope.api.model.Plugin
import com.github.gnastnosaj.filter.kaleidoscope.api.KaleidoscopeRetrofit
import com.github.gnastnosaj.filter.kaleidoscope.api.search.search
import com.github.gnastnosaj.filter.kaleidoscope.ui.adapter.CatalogAdapter
import com.github.gnastnosaj.filter.kaleidoscope.ui.view.materialSearchView
import com.github.gnastnosaj.filter.kaleidoscope.ui.view.tagGroup
import com.github.gnastnosaj.filter.kaleidoscope.util.ShareHelper
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.material_design_iconic_typeface_library.MaterialDesignIconic
import com.trello.rxlifecycle2.android.ActivityEvent
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import org.jetbrains.anko.*
import org.jetbrains.anko.appcompat.v7.toolbar
import org.jetbrains.anko.design.tabLayout
import org.jetbrains.anko.design.themedAppBarLayout
import org.jetbrains.anko.support.v4.viewPager


class CatalogActivity : BaseActivity() {
    private var searchView: MaterialSearchView? = null
    private var searchDisposable: Disposable? = null

    private var progressBar: ProgressBar? = null

    private var plugin: Plugin? = null
    private var catalog: Catalog? = null

    companion object {
        const val EXTRA_CATALOG_HASH_CODE = "catalog"
        const val EXTRA_PLUGIN = "plugin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.hasExtra(EXTRA_PLUGIN)) {
            plugin = intent.getParcelableExtra(EXTRA_PLUGIN)
        }
        if (intent.hasExtra(EXTRA_CATALOG_HASH_CODE)) {
            catalog = Kaleidoscope.restoreInstanceState(intent.getIntExtra(EXTRA_CATALOG_HASH_CODE, -1))
        }

        var tabLayout: TabLayout? = null
        var viewPager: ViewPager? = null

        frameLayout {
            linearLayout {
                orientation = LinearLayout.VERTICAL
                themedAppBarLayout(R.style.AppTheme_AppBarOverlay) {
                    setSupportActionBar(toolbar {
                        popupTheme = R.style.AppTheme_PopupOverlay
                    })
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    title = plugin?.name
                    progressBar = horizontalProgressBar(R.style.Widget_AppCompat_ProgressBar_Horizontal) {
                        scaleY = 0.5f
                        isIndeterminate = true
                        visibility = View.GONE
                    }.lparams(matchParent, wrapContent)
                    tabLayout = tabLayout()
                }
                frameLayout {
                    viewPager = viewPager {
                        id = R.id.catalog_view_pager
                    }.lparams(matchParent, matchParent)
                }.lparams {
                    width = matchParent
                    height = 0
                    weight = 1.0f
                }
            }.lparams(matchParent, matchParent)

            searchView = materialSearchView {
                val suggestionsListView = findViewById<ListView>(R.id.suggestion_list)
                if (suggestionsListView.headerViewsCount == 0) {
                    suggestionsListView.addHeaderView(with(AnkoContext.create(context)) {
                        frameLayout {
                            imageView {
                                setImageDrawable(IconicsDrawable(context)
                                        .icon(MaterialDesignIconic.Icon.gmi_delete)
                                        .color(Color.WHITE).sizeDp(18))
                            }.lparams {
                                gravity = Gravity.END
                                setMargins(0, dip(12), dip(12), dip(12))
                            }
                        }
                    })
                }
                prepareSuggestions()
                setOnQueryTextListener(object : MaterialSearchView.OnQueryTextListener {
                    override fun onQueryTextChange(newText: String?): Boolean {
                        return false
                    }

                    override fun onQueryTextSubmit(query: String?): Boolean {
                        searchDisposable?.apply {
                            if (!isDisposed) {
                                dispose()
                            }
                        }
                        query?.let {
                            progressBar?.visibility = View.VISIBLE
                            searchDisposable = search(it).subscribe({
                                progressBar?.visibility = View.GONE
                            }, {
                                progressBar?.visibility = View.GONE
                            })
                            return true
                        }
                        return false
                    }
                })
                setOnItemClickListener { _, _, position, _ ->
                    if (position == 0) {
                        searchView?.clearAll()
                    } else {
                        searchDisposable?.apply {
                            if (!isDisposed) {
                                dispose()
                            }
                        }
                        progressBar?.visibility = View.VISIBLE
                        searchDisposable = search(getSuggestionAtPosition(position - 1)).subscribe({
                            progressBar?.visibility = View.GONE
                        }, {
                            progressBar?.visibility = View.GONE
                        })
                    }
                    closeSearch()
                }
            }.lparams(matchParent, matchParent)
        }

        var observable: Observable<Catalog>? = null
        catalog?.let {
            observable = Observable.just(it)
        }
        plugin?.id?.let { id ->
            observable = KaleidoscopeRetrofit.instance.service
                    .plugin(id)
                    .map { script ->
                        Project(script)
                    }
                    .map { project ->
                        project.execute("area", resources.getString(R.string.area))
                        project.execute("build")
                        project.catalog?.connections?.let {
                            if (it.isNotEmpty()) {
                                return@map project.catalog!!
                            }
                        }
                        throw IllegalStateException()
                    }
        }
        observable?.apply {
            compose(RxHelper.rxSchedulerHelper())
                    .compose(bindUntilEvent(ActivityEvent.DESTROY))
                    .subscribe { catalog ->
                        val catalogAdapter = CatalogAdapter(this@CatalogActivity, supportFragmentManager, catalog)
                        viewPager?.let {
                            it.adapter = catalogAdapter
                            tabLayout?.setupWithViewPager(it)
                        }
                        catalog.categories?.let { categories ->
                            if (categories.size > 0) {
                                prepareCategories(categories)
                            }
                        }
                    }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_catalog, menu)
        menu?.findItem(R.id.action_search)?.icon = IconicsDrawable(this)
                .icon(MaterialDesignIconic.Icon.gmi_search)
                .color(Color.WHITE).sizeDp(18)
        menu?.findItem(R.id.action_share)?.icon = IconicsDrawable(this)
                .icon(MaterialDesignIconic.Icon.gmi_share)
                .color(Color.WHITE).sizeDp(18)
        menu?.findItem(R.id.action_favourite)?.icon = IconicsDrawable(this)
                .icon(MaterialDesignIconic.Icon.gmi_label_heart)
                .color(Color.WHITE).sizeDp(18)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_search -> {
                searchView?.openSearch()
                true
            }
            R.id.action_share -> {
                ShareHelper.share(this, ShareParamText(resources.getString(R.string.action_share), resources.getString(R.string.share_kaleidoscope)))
                true
            }
            R.id.action_favourite -> {
                true
            }
            R.id.action_about -> {
                startActivity(intentFor<AboutActivity>())
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    override fun onBackPressed() {
        searchView?.apply {
            if (isOpen) {
                closeSearch()
                return
            }
        }
        super.onBackPressed()
    }

    private fun prepareCategories(categories: List<Category>) {
        searchView?.findViewById<ListView>(R.id.suggestion_list)?.addFooterView(with(AnkoContext.create(this)) {
            linearLayout {
                layoutParams = AbsListView.LayoutParams(matchParent, wrapContent)
                orientation = LinearLayout.VERTICAL
                categories.filter {
                    it.children?.isEmpty() ?: true
                }.let { categories ->
                    if (categories.isNotEmpty()) {
                        tagGroup(R.style.TagGroup_Beauty_Red_Inverse_Hacky) {
                            padding = dip(12)
                            setTags(categories.map {
                                it.name
                            })
                            setOnTagClickListener { tag ->
                                categories.firstOrNull {
                                    it.name == tag
                                }?.connection?.apply {
                                    startActivity(Intent(intentFor<WaterfallActivity>(WaterfallActivity.EXTRA_TITLE to tag, WaterfallActivity.EXTRA_CONNECTION_HASH_CODE to Kaleidoscope.saveInstanceState(this))))
                                }
                            }
                        }.lparams(matchParent, wrapContent)
                    }
                }
                categories.filter {
                    it.children?.isNotEmpty() ?: false
                }.forEach {
                    textView {
                        setPadding(dip(12), 0, dip(12), 0)
                        textSize = 14f
                        textColorResource = R.color.colorPrimaryDark
                        text = it.name
                    }.lparams(matchParent, wrapContent)
                    tagGroup(R.style.TagGroup_Beauty_Red_Inverse_Hacky) {
                        padding = dip(12)
                        val children = it.children
                        setTags(children?.map {
                            it.name
                        })
                        setOnTagClickListener { tag ->
                            children?.firstOrNull {
                                it.name == tag
                            }?.connection?.let { connection ->
                                startActivity(Intent(intentFor<WaterfallActivity>(WaterfallActivity.EXTRA_TITLE to tag, WaterfallActivity.EXTRA_CONNECTION_HASH_CODE to Kaleidoscope.saveInstanceState(connection))))
                            }
                        }
                    }.lparams(matchParent, wrapContent)
                }
            }
        })
    }
}