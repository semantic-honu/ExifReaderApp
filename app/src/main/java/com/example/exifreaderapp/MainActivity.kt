package com.example.exifreaderapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.exifreaderapp.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            openImagePicker()
        } else {
            Toast.makeText(this, "画像と位置情報へのアクセス権限が必要です。", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            binding.imageView.post {
                viewModel.loadNewImage(uri, binding.imageView.width, binding.imageView.height)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeUiState()
    }

    private fun setupUI() {
        binding.selectImageButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.deleteExifButton.setOnClickListener {
            viewModel.deleteExif()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    if (state.bitmap != null) {
                        binding.imageView.setImageBitmap(state.bitmap)
                    } else {
                        binding.imageView.setImageResource(R.drawable.ic_photo_library)
                    }

                    binding.exifTextView.text = state.exifInfo

                    binding.addressTextView.text = state.addressInfo
                    binding.addressTextView.visibility = if (state.addressInfo != null) View.VISIBLE else View.GONE

                    state.toastMessage?.let {
                        Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show()
                        viewModel.onToastShown()
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            openImagePicker()
        } else {
            requestPermissionsLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun openImagePicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
