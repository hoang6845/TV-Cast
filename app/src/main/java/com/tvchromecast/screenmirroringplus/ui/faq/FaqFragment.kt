package com.tvchromecast.screenmirroringplus.ui.faq

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.FragmentFaqBinding
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack

class FaqFragment : BaseFragment<FragmentFaqBinding, FaqViewModel>() {

    private val faqAdapter by lazy { FaqAdapter() }

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.topBar)
        adjustInsetsForBottomMargin(binding.supportCard)
        binding.rvFaq.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFaq.adapter = faqAdapter
        faqAdapter.submitList(FaqContent.items)
    }

    override fun initListener() {
        onBackPressed { popBackStack() }
        binding.btnBack.setOnClickListener { popBackStack() }
        binding.btnSupport.setOnClickListener { openSupportEmail() }
    }

    override fun initData() = Unit

    private fun openSupportEmail() {
        val email = FaqContent.SUPPORT_EMAIL
        if (email.isBlank()) {
            Toast.makeText(requireContext(), R.string.text_faq_support_email_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.text_faq_support_subject))
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.text_support)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.text_web_page_not_supported, Toast.LENGTH_SHORT).show()
        }
    }
}
