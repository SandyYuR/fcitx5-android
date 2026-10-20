/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivitySetupBinding
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.firstUndonePage
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.isLastPage
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.notificationManager

class SetupActivity : SettingsUiActivity() {

    private lateinit var binding: ActivitySetupBinding

    private lateinit var viewPager: ViewPager2

    private lateinit var skipButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            // SettingsUiActivity owns side/navigation/IME insets; this page owns the status bar.
            binding.root.updatePadding(top = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            windowInsets
        }
        setContentView(binding.root)
        binding.appTitle.text = AppUtil.appLabel(this)
        skipButton = binding.skipButton.apply {
            text = getString(R.string.skip)
            setOnClickListener { finish() }
        }
        prevButton = binding.prevButton.apply {
            text = getString(R.string.prev)
            setOnClickListener { viewPager.currentItem -= 1 }
        }
        nextButton = binding.nextButton.apply {
            setOnClickListener {
                if (viewPager.currentItem != SetupPage.entries.size - 1)
                    viewPager.currentItem += 1
                else finish()
            }
        }
        viewPager = binding.viewpager.apply {
            adapter = Adapter()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateButtons()
            })
        }
        // skip to undone page
        if (savedInstanceState == null) {
            firstUndonePage()?.let { viewPager.setCurrentItem(it.ordinal, false) }
        }
        updateButtons()
        shown = true
        createNotificationChannel()
    }

    private fun updateButtons() {
        val allDone = !SetupPage.hasUndonePage()
        val isFirstPage = viewPager.currentItem == 0
        val isLastPage = viewPager.currentItem.isLastPage()

        prevButton.visibility = if (isFirstPage) View.GONE else View.VISIBLE
        skipButton.visibility = if (allDone) View.GONE else View.VISIBLE
        nextButton.text = getString(if (isLastPage) R.string.done else R.string.next)
        nextButton.visibility = if (isLastPage && !allDone) View.GONE else View.VISIBLE
        binding.stepCounter.text = getString(R.string.setup_step_counter,
            viewPager.currentItem + 1, SetupPage.entries.size)
        binding.stepProgress.max = SetupPage.entries.size
        binding.stepProgress.progress = viewPager.currentItem + 1
        binding.stepProgress.contentDescription = binding.stepCounter.text
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        supportFragmentManager.fragments.filterIsInstance<SetupFragment>().forEach { it.sync() }
        updateButtons()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.setup_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onPause() {
        if (SetupPage.hasUndonePage())
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_keyboard_24)
                .setContentTitle(AppUtil.appLabel(this))
                .setContentText(getText(R.string.setup_keyboard))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        super.onPause()
    }

    override fun onResume() {
        notificationManager.cancel(NOTIFY_ID)
        super.onResume()
        supportFragmentManager.fragments.filterIsInstance<SetupFragment>().forEach { it.sync() }
        updateButtons()
    }

    private inner class Adapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment =
            SetupFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(SetupFragment.PAGE, SetupPage.valueOf(position))
                }
            }
    }

    companion object {
        private var shown = false
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 233
        fun shouldShowUp() = !shown && SetupPage.hasUndonePage()
    }
}
