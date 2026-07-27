package com.tvchromecast.screenmirroringplus.ui.home

import com.tvchromecast.screenmirroringplus.databinding.ItemHomeBinding
import com.tvchromecast.screenmirroringplus.model.entity.ItemFunc
import hoang.dqm.codebase.base.adapter.BaseRecyclerViewAdapter

class HomeFuncAdapter: BaseRecyclerViewAdapter<ItemFunc, ItemHomeBinding>() {
    override fun bindData(
        binding: ItemHomeBinding,
        item: ItemFunc,
        position: Int
    ) {
        binding.icFunc.setImageResource(item.iconRes)
        binding.titleFunc.text = item.title
        binding.desFunc.text = item.description
    }

    fun onClickItem(listener: (item: ItemFunc, position: Int) -> Unit){
        setOnClickItemListener = listener
    }
}