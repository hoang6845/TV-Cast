package com.tvchromecast.screenmirroringplus.ui.language_activity

import androidx.core.content.ContextCompat
import android.view.ViewGroup
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.adapter.BaseRecyclerViewAdapter
import hoang.dqm.codebase.data.LanguageSelector
import hoang.dqm.codebase.databinding.ItemLanguageBinding

class LanguageAdapter : BaseRecyclerViewAdapter<LanguageSelector, ItemLanguageBinding>() {
    override fun inflateBinding(parent: ViewGroup): ItemLanguageBinding {
        return ItemLanguageBinding.inflate(getLayoutInflater(parent.context), parent, false)
    }

    override fun bindData(binding: ItemLanguageBinding, item: LanguageSelector, position: Int) {
        binding.tvLanguage.text = item.language.language
        binding.imvLanguage.setImageResource(item.language.flag)
        binding.root.background = if (item.isCheck) {
            ContextCompat.getDrawable(context, R.drawable.bg_lang_selected)
        } else {
            ContextCompat.getDrawable(context, R.drawable.bg_lang_unselected)
        }

        binding.imvCheck.setImageResource(
            if (item.isCheck) R.drawable.ic_lang_checked
            else R.drawable.ic_lang_unchecked
        )
    }

    fun setSelectLang(lang: LanguageSelector) {
        val updatedPositions = mutableListOf<Int>()
        dataList.forEachIndexed { index, item ->
            val shouldCheck = lang.language.languageCode == item.language.languageCode
            if (item.isCheck != shouldCheck) {
                item.isCheck = shouldCheck
                updatedPositions.add(index)
            }
        }
        updatedPositions.forEach { position ->
            notifyItemChanged(position)
        }
    }
}
