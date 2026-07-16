package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.camera.VirtualCameraService
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    // This method is hooked by our Xposed module to return true when loaded.
    // This allows the app UI to dynamically display whether the module is active.
    fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFFF7F9FC) // Sleek light background
                ) { innerPadding ->
                    VCamProDashboard(
                        isModuleActive = isModuleActive(),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VCamProDashboard(isModuleActive: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // Core state management
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoPath by remember { mutableStateOf("") }
    var isServiceActive by remember { mutableStateOf(VirtualCameraService.isServiceRunning) }
    
    // Live filter parameters
    var isLoopEnabled by remember { mutableStateOf(true) }
    var brightnessOffset by remember { mutableStateOf(0f) }
    var isBlurEnabled by remember { mutableStateOf(false) }

    // Overlay and Storage Permissions
    var hasOverlayPermission by remember { 
        mutableStateOf(Settings.canDrawOverlays(context)) 
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Refresh state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isServiceActive = VirtualCameraService.isServiceRunning
            hasOverlayPermission = Settings.canDrawOverlays(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    // File copy helper (creates a readable copy of selected content video for the hook)
    fun getRealPathFromURI(uri: Uri): String {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return ""
            val ext = "mp4" // standard format
            val tempFile = File(context.getExternalFilesDir(null), "vcam_stream.$ext")
            val outputStream = FileOutputStream(tempFile)
            
            val buffer = ByteArray(1024 * 4)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            // Save config as well
            VirtualCameraService.saveConfig(
                context, 
                tempFile.absolutePath, 
                isLoopEnabled, 
                brightnessOffset, 
                isBlurEnabled
            )
            
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying video stream file: ${e.message}")
            ""
        }
    }

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoPath = getRealPathFromURI(uri)
            Toast.makeText(context, "Video Selected & Configured!", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F9FC))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header conforming to Sleek Interface specification
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Logo Box
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF4F46E5)), // Indigo primary accent
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "App logo",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "VCam Pro",
                            color = Color(0xFF1E293B),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isModuleActive) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Text(
                                text = if (isModuleActive) "LSPosed Hook Active" else "Module Inactive",
                                color = if (isModuleActive) Color(0xFF059669) else Color(0xFFDC2626),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                
                // Settings/Info circle button
                IconButton(
                    onClick = {
                        Toast.makeText(context, "VCam Pro Engine v1.0 • Built with Kotlin", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFF1F5F9))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50))
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Live Video Preview Box (Deep Slate/Dark theme as per the design spec)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(32.dp), // Distinctive rounded corners
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // Rich dark theme
            border = BorderStroke(1.dp, Color(0xFF334155)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (selectedVideoUri != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(selectedVideoUri)
                                setOnPreparedListener { mediaPlayer ->
                                    mediaPlayer.isLooping = true
                                    mediaPlayer.start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(32.dp)),
                        update = { view ->
                            view.setVideoURI(selectedVideoUri)
                        }
                    )
                    
                    // Top Bar overlay descriptors
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "PREVIEW OUTPUT", 
                                color = Color.White, 
                                fontSize = 9.sp, 
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "1080P | 30FPS", 
                                color = Color.White, 
                                fontSize = 9.sp, 
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Bottom indicator decoration
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(2.dp)
                                    .background(Color(0xFF6366F1), RoundedCornerShape(50))
                            )
                            Text(
                                "LIVE STREAM MODE", 
                                color = Color(0xFF818CF8), 
                                fontSize = 9.sp, 
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Ready to Inject",
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Ready to Inject",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Media Source Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MEDIA SOURCE",
                        color = Color(0xFF475569),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    TextButton(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        modifier = Modifier.testTag("select_video_button")
                    ) {
                        Text(
                            text = if (selectedVideoUri != null) "CHANGE" else "CHOOSE VIDEO",
                            color = Color(0xFF4F46E5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (selectedVideoUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = "Active selection",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedVideoUri?.lastPathSegment ?: "vcam_stream.mp4",
                                color = Color(0xFF1E293B),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "High Quality • Loop Enabled",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                            .clickable { videoPickerLauncher.launch("video/*") }
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Tap to configure virtual video feed...",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Live Pipeline Parameters Configuration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "⚙️ LIVE VIDEO PIPELINE CONFIG",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                // Loop Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto Loop Video Stream", color = Color(0xFF1E293B), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Continually stream video on loop", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                    Switch(
                        checked = isLoopEnabled,
                        onCheckedChange = { 
                            isLoopEnabled = it
                            if (selectedVideoUri != null) {
                                getRealPathFromURI(selectedVideoUri!!)
                            }
                        }
                    )
                }

                // Brightness
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stream Brightness Offset", color = Color(0xFF1E293B), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = String.format("%.2f", brightnessOffset),
                            color = Color(0xFF4F46E5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = brightnessOffset,
                        onValueChange = { 
                            brightnessOffset = it
                            if (selectedVideoUri != null) {
                                getRealPathFromURI(selectedVideoUri!!)
                            }
                        },
                        valueRange = -1.0f..1.0f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF4F46E5),
                            thumbColor = Color(0xFF4F46E5)
                        )
                    )
                }

                // Soft-Blur Filter Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Live Soft-Blur Filter", color = Color(0xFF1E293B), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Simulate depth-of-field blur on feed", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                    Switch(
                        checked = isBlurEnabled,
                        onCheckedChange = { 
                            isBlurEnabled = it
                            if (selectedVideoUri != null) {
                                getRealPathFromURI(selectedVideoUri!!)
                            }
                        }
                    )
                }
            }
        }

        // Floating Footer Controller Panel conforming to Sleek Interface specification
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Toggles Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Loop circle badge
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { 
                                isLoopEnabled = !isLoopEnabled 
                                if (selectedVideoUri != null) {
                                    getRealPathFromURI(selectedVideoUri!!)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (isLoopEnabled) Color(0xFFEEF2F6) else Color.White)
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Loop,
                                contentDescription = "Loop Toggle",
                                tint = if (isLoopEnabled) Color(0xFF4F46E5) else Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = "LOOP",
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Fit circle badge
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                Toast.makeText(context, "Aspect Ratio: Native 16:9 Fit", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Aspect Toggle",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = "FIT",
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Audio circle badge
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                Toast.makeText(context, "Audio Streaming: Active Sync", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Audio Toggle",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = "AUDIO",
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Interactive Primary Buttons
                if (isServiceActive) {
                    Button(
                        onClick = {
                            val serviceIntent = Intent(context, VirtualCameraService::class.java).apply {
                                action = VirtualCameraService.ACTION_STOP
                            }
                            context.stopService(serviceIntent)
                            isServiceActive = false
                            Toast.makeText(context, "Virtual Camera engine stopped", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), // Sleek red stop
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("stop_vcam_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                            Text("STOP VIRTUAL STREAM", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (selectedVideoUri == null) {
                                Toast.makeText(context, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (!hasCameraPermission) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                return@Button
                            }
                            if (!hasOverlayPermission) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                return@Button
                            }
                            
                            // Refresh and write latest config data
                            selectedVideoPath = getRealPathFromURI(selectedVideoUri!!)
                            
                            val serviceIntent = Intent(context, VirtualCameraService::class.java).apply {
                                action = VirtualCameraService.ACTION_START
                                putExtra(VirtualCameraService.EXTRA_VIDEO_URI, selectedVideoUri.toString())
                            }
                            ContextCompat.startForegroundService(context, serviceIntent)
                            isServiceActive = true
                            Toast.makeText(context, "Virtual Camera engine running!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)), // Elegant Sleek Indigo primary
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("start_vcam_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                            Text("START VIRTUAL CAMERA", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Guide / Manual Instruction Details Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "📖 PANDUAN PENGGUNAAN & TEKNIS",
                    color = Color(0xFF1E293B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Divider(color = Color(0xFFF1F5F9))

                Text(
                    text = "Aplikasi ini bekerja dengan dua metode alternatif: NON-ROOT (Floating Preview Helper) & ROOT/LSPosed (Camera Injection).",
                    color = Color(0xFF475569),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Text(
                    text = "⚙️ METODE 1: LSPosed / Xposed Module (Sangat Direkomendasikan untuk TikTok/Shopee Live)",
                    color = Color(0xFF059669),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "1. Pastikan perangkat Anda sudah ROOT menggunakan Magisk / KernelSU.\n" +
                           "2. Install module Zygisk - LSPosed pada manager Magisk Anda.\n" +
                           "3. Aktifkan module \"VCam Pro\" di dalam app LSPosed.\n" +
                           "4. Centang aplikasi target (TikTok, Shopee, WhatsApp, dll) di list target LSPosed.\n" +
                           "5. Reboot device Anda.\n" +
                           "6. Pilih video MP4 di dashboard VCam Pro, lalu tekan START.\n" +
                           "7. Buka TikTok/Shopee Live, maka input kamera fisik secara otomatis akan digantikan dengan video yang Anda pilih secara realtime!",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Text(
                    text = "📱 METODE 2: Non-Root (Overlay Mode)",
                    color = Color(0xFF2563EB),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "1. Berikan izin Overlay Window.\n" +
                           "2. Pilih video Anda, tekan START VCAM.\n" +
                           "3. Controller overlay melayang akan muncul di layar. Anda dapat memposisikan controller ini di atas kamera app lain untuk siaran stream semi-otomatis.",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

