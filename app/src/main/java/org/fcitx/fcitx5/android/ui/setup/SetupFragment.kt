/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.FragmentSetupBinding
import org.fcitx.fcitx5.android.utils.serializable

class SetupFragment : Fragment() {

    private var binding: FragmentSetupBinding? = null

    private val page: SetupPage by lazy { requireArguments().serializable(PAGE)!! }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val pageBinding = FragmentSetupBinding.inflate(inflater, container, false)
        binding = pageBinding
        pageBinding.pageTitle.text = page.getButtonText(requireContext())
        pageBinding.pageIcon.setImageResource(when (page) {
            SetupPage.Enable -> R.drawable.ic_baseline_keyboard_24
            SetupPage.Select -> R.drawable.ic_baseline_check_circle_24
        })
        sync()
        return pageBinding.root
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    // called on window focus changed
    fun sync() {
        val binding = binding ?: return
        val done = page.isDone()
        with(binding) {
            hintText.text = page.getHintText(requireContext())
            actionButton.visibility = if (done) View.GONE else View.VISIBLE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
            completionCard.visibility = if (done) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val PAGE = "page"
    }

}
