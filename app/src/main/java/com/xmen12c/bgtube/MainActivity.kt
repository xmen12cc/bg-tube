package com.xmen12c.bgtube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var linkInput: TextInputEditText
    private lateinit var playButton: MaterialButton
    private lateinit var toggleButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var recentsLabel: TextView
    lateinit var videoContainer: FrameLayout
        private set

    private lateinit var recentsGroup: ChipGroup

    private val stateListener = { state: PlayerHub.State -> runOnUiThread { render(state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        linkInput = findViewById(R.id.link_input)
        playButton = findViewById(R.id.play_button)
        toggleButton = findViewById(R.id.toggle_button)
        stopButton = findViewById(R.id.stop_button)
        statusText = findViewById(R.id.status_text)
        recentsLabel = findViewById(R.id.recents_label)
        recentsGroup = findViewById(R.id.recents_group)
        videoContainer = findViewById(R.id.video_container)

        playButton.setOnClickListener {
            val id = VideoId.extract(linkInput.text?.toString().orEmpty())
            if (id == null) {
                Toast.makeText(this, R.string.toast_bad_link, Toast.LENGTH_SHORT).show()
            } else {
                startPlay(id)
            }
        }
        linkInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                playButton.performClick()
                true
            } else {
                false
            }
        }
        toggleButton.setOnClickListener { PlaybackService.toggle() }
        stopButton.setOnClickListener { PlaybackService.stopFromUi() }

        renderRecents()
        render(PlayerHub.state)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PlaybackService.hostActivity(this)
        PlayerHub.observe(stateListener)
    }

    override fun onPause() {
        PlayerHub.unobserve(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        PlaybackService.clearHost(this)
        PlaybackService.instance?.detach()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val id = when (intent?.action) {
            Intent.ACTION_SEND -> VideoId.extract(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            else -> intent?.getStringExtra(PlaybackService.EXTRA_VIDEO_ID)
        } ?: return
        linkInput.setText("https://youtu.be/$id")
        startPlay(id)
    }

    private fun startPlay(id: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        RecentsStore.touch(this, id, null)
        renderRecents()
        val intent = Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY)
            .putExtra(PlaybackService.EXTRA_VIDEO_ID, id)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(state: PlayerHub.State) {
        val has = state.videoId != null
        videoContainer.visibility = if (has) View.VISIBLE else View.GONE
        toggleButton.visibility = if (has) View.VISIBLE else View.GONE
        stopButton.isEnabled = has
        statusText.text = when {
            state.notice != null -> state.notice
            !has -> getString(R.string.status_idle)
            state.title == null -> getString(R.string.status_loading)
            state.isPlaying -> getString(R.string.status_playing, state.title)
            else -> getString(R.string.status_paused, state.title)
        }
        toggleButton.setIconResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun renderRecents() {
        recentsGroup.removeAllViews()
        val items = RecentsStore.all(this)
        recentsLabel.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (item in items) {
            val chip = Chip(this)
            chip.text = item.title ?: item.id
            chip.isCheckable = false
            chip.setOnClickListener { startPlay(item.id) }
            recentsGroup.addView(chip)
        }
    }
}
