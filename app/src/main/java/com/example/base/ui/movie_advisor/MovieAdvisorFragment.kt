package com.example.base.ui.movie_advisor

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.base.R
import com.example.base.databinding.FragmentMovieAdvisorBinding
import com.example.base.databinding.LayoutMovieAdvisorOptionSheetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import hoang.dqm.codebase.utils.collectLatestFlow

class MovieAdvisorFragment : BaseFragment<FragmentMovieAdvisorBinding, MovieAdvisorViewModel>() {

    private val resultAdapter by lazy { MovieAdvisorResultAdapter() }
    private var currentUiState = MovieAdvisorUiState()

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.topBar)
        setupFilterRows()
        binding.rvMovieResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMovieResults.adapter = resultAdapter
    }

    override fun initListener() {
        onBackPressed { handleBackPress() }
        binding.btnBack.setOnClickListener { handleBackPress() }
        binding.btnTopAction.setOnClickListener { handleTopAction() }
        binding.btnAll.setOnClickListener { viewModel.selectContentType(MovieAdvisorContentType.ALL) }
        binding.btnMovies.setOnClickListener { viewModel.selectContentType(MovieAdvisorContentType.MOVIES) }
        binding.btnSeries.setOnClickListener { viewModel.selectContentType(MovieAdvisorContentType.SERIES) }
        binding.rowGenres.root.setOnClickListener { showGenresSheet() }
        binding.rowMoods.root.setOnClickListener { showMoodsSheet() }
        binding.rowReleaseYear.root.setOnClickListener { showYearSheet() }
        binding.rowRating.root.setOnClickListener { showRatingSheet() }
        binding.btnGetList.setOnClickListener {
            viewModel.getRecommendations(binding.etDescription.text?.toString().orEmpty())
        }
        binding.btnFindNext.setOnClickListener {
            viewModel.findNext(binding.etDescription.text?.toString().orEmpty())
        }
        binding.btnEditFilters.setOnClickListener { viewModel.backToEdit() }
        binding.btnBroadenSearch.setOnClickListener {
            viewModel.selectRating(MovieAdvisorDefaults.ratingOptions.first())
            viewModel.getRecommendations(binding.etDescription.text?.toString().orEmpty())
        }
        binding.btnTryAgain.setOnClickListener {
            viewModel.getRecommendations(binding.etDescription.text?.toString().orEmpty())
        }
        binding.etDescription.doOnTextChanged { _, _, _, _ -> renderGetListLabel() }
    }

    override fun initData() {
        collectLatestFlow(viewModel.uiState) { state ->
            currentUiState = state
            renderUiState(state)
        }
    }

    private fun renderUiState(state: MovieAdvisorUiState) {
        renderMode(state.resultState)
        renderTabs(state.contentType)
        binding.rowGenres.tvFilterValue.text = selectedListLabel(state.selectedGenres, "All Genres")
        binding.rowMoods.tvFilterValue.text = selectedListLabel(state.selectedMoods, "All Moods")
        binding.rowReleaseYear.tvFilterValue.text = state.selectedYearRange.label
        binding.rowRating.tvFilterValue.text = state.selectedRating.label
        binding.btnFindNext.isVisible = state.canFindNext &&
            (state.resultState is MovieAdvisorResultState.Success ||
                state.resultState is MovieAdvisorResultState.Empty)
        renderResultState(state.resultState)
        renderGetListLabel()
    }

    private fun setupFilterRows() {
        binding.rowGenres.tvFilterTitle.text = getString(R.string.text_genres)
        binding.rowGenres.tvFilterValue.text = "All Genres"
        binding.rowGenres.ivFilterIcon.setImageResource(R.drawable.ic_movie_genre)
        binding.rowGenres.iconContainer.setBackgroundResource(R.drawable.bg_movie_advisor_icon_genre)

        binding.rowMoods.tvFilterTitle.text = getString(R.string.text_moods)
        binding.rowMoods.tvFilterValue.text = "All Moods"
        binding.rowMoods.ivFilterIcon.setImageResource(R.drawable.ic_movie_mood)
        binding.rowMoods.iconContainer.setBackgroundResource(R.drawable.bg_movie_advisor_icon_mood)

        binding.rowReleaseYear.tvFilterTitle.text = getString(R.string.text_release_year)
        binding.rowReleaseYear.tvFilterValue.text = MovieAdvisorDefaults.yearRanges.first().label
        binding.rowReleaseYear.ivFilterIcon.setImageResource(R.drawable.ic_movie_calendar)
        binding.rowReleaseYear.iconContainer.setBackgroundResource(R.drawable.bg_movie_advisor_icon_year)

        binding.rowRating.tvFilterTitle.text = getString(R.string.text_imdb_rating)
        binding.rowRating.tvFilterValue.text = MovieAdvisorDefaults.ratingOptions.first().label
        binding.rowRating.ivFilterIcon.setImageResource(R.drawable.ic_movie_star)
        binding.rowRating.iconContainer.setBackgroundResource(R.drawable.bg_movie_advisor_icon_rating)
    }

    private fun renderMode(resultState: MovieAdvisorResultState) {
        val showingResults = resultState is MovieAdvisorResultState.Success ||
            resultState is MovieAdvisorResultState.Empty ||
            resultState is MovieAdvisorResultState.Error
        binding.tvTitle.text = getString(
            if (showingResults) R.string.text_movie_list else R.string.text_movie_advisor
        )

        binding.editorContainer.isVisible = !showingResults
        binding.resultContainer.isVisible = showingResults
        binding.loadingOverlay.isVisible = resultState == MovieAdvisorResultState.Loading
    }

    private fun renderTabs(type: MovieAdvisorContentType) {
        renderTab(binding.btnAll, type == MovieAdvisorContentType.ALL)
        renderTab(binding.btnMovies, type == MovieAdvisorContentType.MOVIES)
        renderTab(binding.btnSeries, type == MovieAdvisorContentType.SERIES)
    }

    private fun renderTab(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_iptv_tab_selected else 0)
        view.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }

    private fun renderResultState(resultState: MovieAdvisorResultState) {
        binding.rvMovieResults.isVisible = resultState is MovieAdvisorResultState.Success
        binding.emptyState.isVisible = resultState is MovieAdvisorResultState.Empty
        binding.errorState.isVisible = resultState is MovieAdvisorResultState.Error

        when (resultState) {
            is MovieAdvisorResultState.Success -> resultAdapter.submitList(resultState.items)
            is MovieAdvisorResultState.Error -> binding.tvErrorMessage.text = resultState.message
            else -> Unit
        }
    }

    private fun renderGetListLabel() {
        val hasCustomFilters = currentUiState.selectedGenres.isNotEmpty() ||
            currentUiState.selectedMoods.isNotEmpty() ||
            currentUiState.selectedYearRange != MovieAdvisorDefaults.yearRanges.first() ||
            currentUiState.selectedRating != MovieAdvisorDefaults.ratingOptions.first() ||
            currentUiState.contentType != MovieAdvisorContentType.ALL ||
            binding.etDescription.text?.isNotBlank() == true
        binding.btnGetList.text = getString(
            if (hasCustomFilters) R.string.text_get_list else R.string.text_get_popular_list
        )
    }

    private fun showGenresSheet() {
        showOptionSheet(
            title = getString(R.string.text_genres),
            options = listOf("All Genres") + MovieAdvisorDefaults.genres,
            selected = if (currentUiState.selectedGenres.isEmpty()) {
                setOf("All Genres")
            } else {
                currentUiState.selectedGenres
            },
            multiSelect = true
        ) { selected ->
            viewModel.selectGenres(selected.filterNot { it == "All Genres" }.toSet())
        }
    }

    private fun showMoodsSheet() {
        showOptionSheet(
            title = getString(R.string.text_moods),
            options = listOf("All Moods") + MovieAdvisorDefaults.moods,
            selected = if (currentUiState.selectedMoods.isEmpty()) {
                setOf("All Moods")
            } else {
                currentUiState.selectedMoods
            },
            multiSelect = true
        ) { selected ->
            viewModel.selectMoods(selected.filterNot { it == "All Moods" }.toSet())
        }
    }

    private fun showYearSheet() {
        showOptionSheet(
            title = getString(R.string.text_release_year),
            options = MovieAdvisorDefaults.yearRanges.map { it.label },
            selected = setOf(currentUiState.selectedYearRange.label),
            multiSelect = false
        ) { selected ->
            val label = selected.firstOrNull() ?: MovieAdvisorDefaults.yearRanges.first().label
            MovieAdvisorDefaults.yearRanges.firstOrNull { it.label == label }
                ?.let(viewModel::selectYearRange)
        }
    }

    private fun showRatingSheet() {
        showOptionSheet(
            title = getString(R.string.text_imdb_rating),
            options = MovieAdvisorDefaults.ratingOptions.map { it.label },
            selected = setOf(currentUiState.selectedRating.label),
            multiSelect = false
        ) { selected ->
            val label = selected.firstOrNull() ?: MovieAdvisorDefaults.ratingOptions.first().label
            MovieAdvisorDefaults.ratingOptions.firstOrNull { it.label == label }
                ?.let(viewModel::selectRating)
        }
    }

    private fun showOptionSheet(
        title: String,
        options: List<String>,
        selected: Set<String>,
        multiSelect: Boolean,
        onDone: (Set<String>) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutMovieAdvisorOptionSheetBinding.inflate(layoutInflater)
        val stagedSelection = selected.toMutableSet()

        fun renderOptions() {
            sheetBinding.optionContainer.removeAllViews()
            options.forEach { option ->
                val row = layoutInflater.inflate(
                    R.layout.item_movie_advisor_option,
                    sheetBinding.optionContainer,
                    false
                ) as TextView
                val isSelected = stagedSelection.contains(option)
                row.text = option
                row.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D7D7DC"))
                row.setBackgroundResource(
                    if (isSelected) R.drawable.bg_movie_advisor_option_selected
                    else R.drawable.bg_movie_advisor_option
                )
                row.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    null,
                    if (isSelected) {
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_iptv_check)
                    } else {
                        null
                    },
                    null
                )
                row.setOnClickListener {
                    if (multiSelect) {
                        if (option.startsWith("All ")) {
                            stagedSelection.clear()
                            stagedSelection += option
                        } else {
                            stagedSelection.removeAll { it.startsWith("All ") }
                            if (stagedSelection.contains(option)) {
                                stagedSelection.remove(option)
                            } else {
                                stagedSelection += option
                            }
                            if (stagedSelection.isEmpty()) stagedSelection += options.first()
                        }
                    } else {
                        stagedSelection.clear()
                        stagedSelection += option
                    }
                    renderOptions()
                }
                sheetBinding.optionContainer.addView(row)
            }
        }

        sheetBinding.tvSheetTitle.text = title
        sheetBinding.btnDone.setOnClickListener {
            onDone(stagedSelection)
            dialog.dismiss()
        }
        renderOptions()

        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            bottomSheet?.let { sheet ->
                BottomSheetBehavior.from(sheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        dialog.show()
    }

    private fun selectedListLabel(selected: Set<String>, allLabel: String): String {
        return when {
            selected.isEmpty() -> allLabel
            selected.size == 1 -> selected.first()
            selected.size == 2 -> selected.joinToString(", ")
            else -> getString(R.string.text_movie_advisor_selected_count, selected.size)
        }
    }

    private fun handleTopAction() {
        if (currentUiState.resultState is MovieAdvisorResultState.Success) {
            Toast.makeText(requireContext(), R.string.text_movie_advisor_list_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.text_movie_advisor_no_saved_lists, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleBackPress() {
        if (currentUiState.resultState is MovieAdvisorResultState.Success ||
            currentUiState.resultState is MovieAdvisorResultState.Empty ||
            currentUiState.resultState is MovieAdvisorResultState.Error
        ) {
            viewModel.backToEdit()
        } else {
            popBackStack()
        }
    }

    companion object {
        fun newInstance() = MovieAdvisorFragment().apply {
            arguments = Bundle()
        }
    }
}
