package com.example.base.ui.movie_advisor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.base.R
import com.example.base.databinding.ItemMovieAdvisorResultBinding

class MovieAdvisorResultAdapter :
    ListAdapter<MovieRecommendation, MovieAdvisorResultAdapter.ResultViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemMovieAdvisorResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(position + 1, getItem(position))
    }

    class ResultViewHolder(
        private val binding: ItemMovieAdvisorResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(index: Int, item: MovieRecommendation) {
            val context = binding.root.context
            val show = item.show
            val year = show.releaseYear?.toString() ?: "Unknown"
            val rating = show.rating?.let { "TVmaze $it" } ?: "No rating"
            val runtime = show.averageRuntime?.let { "$it min" } ?: show.type
            val genres = show.genres.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "No genres"

            binding.tvMovieTitle.text = context.getString(
                R.string.text_movie_advisor_ranked_title,
                index,
                show.name
            )
            binding.tvMovieMeta.text = "$year - ${if (show.isMovieLike) "Movie" else "Series"} - $rating - $runtime"
            binding.tvMovieGenres.text = genres
            binding.tvMovieSummary.text = show.summary.ifBlank {
                context.getString(R.string.text_movie_advisor_no_summary)
            }
            binding.tvMovieMatch.text = context.getString(
                R.string.text_movie_advisor_match_percent,
                item.matchPercent
            )
            binding.tvMovieReason.text = item.reason
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<MovieRecommendation>() {
        override fun areItemsTheSame(
            oldItem: MovieRecommendation,
            newItem: MovieRecommendation
        ): Boolean = oldItem.show.id == newItem.show.id

        override fun areContentsTheSame(
            oldItem: MovieRecommendation,
            newItem: MovieRecommendation
        ): Boolean = oldItem == newItem
    }
}
