package com.rabs.vibesync

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.graphics.RenderEffect
import android.graphics.Shader
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rabs.vibesync.databinding.ActivityMainBinding
import java.io.IOException
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity(), MusicAdapter.OnMusicClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var musicList: ArrayList<MusicModel>
    private lateinit var adapter: MusicAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentIndex = 0
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    @Volatile private var isSeekingByUser = false

    // Declaring UI elements for safe caching and use
    private lateinit var backgroundView: View
    private var handleSongTitle: TextView? = null
    private var handlePlayPauseButton: ImageButton? = null
    private var handlePrevButton: ImageButton? = null
    private var handleNextButton: ImageButton? = null
    private var handleSeekBar: SeekBar? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadMusicFiles()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get the main layout view for blurring
        backgroundView = binding.root.getChildAt(0) // first child (main layout)

        // Bottom sheet (uses ID musicListPanel in your layout)
        bottomSheetBehavior = BottomSheetBehavior.from(binding.musicListPanel)
        bottomSheetBehavior.peekHeight = 100
        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        // Set initial alpha for the bottom sheet background regardless of blur support
        binding.musicListPanel.background?.mutate()?.alpha = 160

        // Implement the dynamic blur and wire up controls, caching views
        setupDynamicBlurAndControls()

        // RecyclerView + Adapter
        musicList = ArrayList()
        adapter = MusicAdapter(musicList, this)
        binding.musicRecycler.layoutManager = LinearLayoutManager(this)
        binding.musicRecycler.adapter = adapter

        // Request correct permission depending on API level
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ActivityCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permissionToRequest)
        } else {
            loadMusicFiles()
        }

        // Main Player Buttons
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnNext.setOnClickListener { playNext() }
        binding.btnPrev.setOnClickListener { playPrevious() }

        // Main SeekBar
        binding.musicSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer!!.seekTo(progress)
                }
                // Also update the bottom sheet handle's seekbar for synchronization
                handleSeekBar?.progress = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekingByUser = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekingByUser = false
            }
        })
    }

    private fun setupDynamicBlurAndControls() {
        // Get the LinearLayout of the bottom sheet handle using its ID defined in bottom_sheet_handle.xml
        val handleView = binding.musicListPanel.findViewById<View>(R.id.bottomSheetPlayer)

        // CACHE ALL VIEWS SAFELY
        handleSongTitle = handleView.findViewById<TextView>(R.id.tvSongTitle)
        handlePlayPauseButton = handleView.findViewById<ImageButton>(R.id.btnPlayPause)
        handlePrevButton = handleView.findViewById<ImageButton>(R.id.btnPrev)
        handleNextButton = handleView.findViewById<ImageButton>(R.id.btnNext)
        handleSeekBar = handleView.findViewById<SeekBar>(R.id.seekBar)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            // Remove blur when fully collapsed
                            backgroundView.setRenderEffect(null)
                        }
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            // Ensure blur is applied when expanded
                            backgroundView.setRenderEffect(
                                RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP)
                            )
                        }
                        else -> {
                            // Do nothing on drag/settling
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    // Apply blur dynamically as the sheet moves from collapsed (0.0)
                    if (slideOffset > 0.0f) {
                        val blurRadius = 40f * slideOffset
                        backgroundView.setRenderEffect(
                            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                        )
                    } else {
                        // Remove blur when at or below peek height
                        backgroundView.setRenderEffect(null)
                    }
                }
            })
        }

        // --- Make bottom sheet controls usable using cached views ---
        handlePlayPauseButton?.setOnClickListener { togglePlayPause() }
        handleNextButton?.setOnClickListener { playNext() }
        handlePrevButton?.setOnClickListener { playPrevious() }

        // SeekBar in the bottom sheet handle
        handleSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer!!.seekTo(progress)
                }
                // Also update the main seekbar for synchronization
                binding.musicSeekBar.progress = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekingByUser = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekingByUser = false
            }
        })
    }

    private fun loadMusicFiles() {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val cursor = contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        musicList.clear()

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val name = it.getString(nameCol) ?: "Unknown"
                val data = it.getString(dataCol) ?: ""
                val uri = ContentUris.withAppendedId(collection, id)
                musicList.add(MusicModel(name, uri, data))
            }
        }
        adapter.notifyDataSetChanged()
    }

    override fun onMusicClick(position: Int) {
        if (position in musicList.indices) {
            currentIndex = position
            playMusicAt(currentIndex)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun playMusicAt(index: Int) {
        if (index !in musicList.indices) return
        val item = musicList[index]

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()
        try {
            mediaPlayer?.apply {
                setDataSource(this@MainActivity, item.uri)
                prepare()
                start()
            }

            // Update song titles for both main player and cached handle view
            binding.tvSongTitle.text = item.title
            handleSongTitle?.text = item.title

            // Set play/pause buttons to PAUSE state for both
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            handlePlayPauseButton?.setImageResource(R.drawable.ic_pause)

            startSeekUpdater()
            mediaPlayer?.setOnCompletionListener { playNext() }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                // Set both buttons to PLAY icon
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                handlePlayPauseButton?.setImageResource(R.drawable.ic_play)
            } else {
                it.start()
                // Set both buttons to PAUSE icon
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                handlePlayPauseButton?.setImageResource(R.drawable.ic_pause)
                startSeekUpdater()
            }
        }
    }

    private fun playNext() {
        if (musicList.isEmpty()) return
        currentIndex = (currentIndex + 1) % musicList.size
        playMusicAt(currentIndex)
    }

    private fun playPrevious() {
        if (musicList.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) musicList.size - 1 else currentIndex - 1
        playMusicAt(currentIndex)
    }

    private fun startSeekUpdater() {
        mediaPlayer?.let { mp ->

            // Set max for both seekbars using cached view
            binding.musicSeekBar.max = mp.duration
            handleSeekBar?.max = mp.duration

            thread {
                // Use a while(true) loop with a try-catch to safely handle the released player
                while (true) {
                    try {
                        if (mp.isPlaying) {
                            if (!isSeekingByUser) {
                                val currentPosition = mp.currentPosition
                                runOnUiThread {
                                    // Update both seekbars using cached view
                                    binding.musicSeekBar.progress = currentPosition
                                    handleSeekBar?.progress = currentPosition
                                }
                            }
                            Thread.sleep(500)
                        } else {
                            // Player finished, paused, or stopped. Exit thread.
                            break
                        }
                    } catch (e: IllegalStateException) {
                        // Crash caught: This happens when the MediaPlayer object is released in the main thread.
                        // We break the loop and exit the thread gracefully.
                        break
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}