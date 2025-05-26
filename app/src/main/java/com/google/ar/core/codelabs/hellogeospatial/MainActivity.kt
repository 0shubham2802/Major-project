private fun launchSplitScreenMode() {
    // Check camera permission
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
        return
    }

    try {
        val splitScreenIntent = Intent(this, SplitScreenActivity::class.java)
        startActivity(splitScreenIntent)
        Toast.makeText(this, "Loading split screen mode - please wait", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch split screen mode", e)
        Toast.makeText(this, "Failed to launch split screen mode: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Set up split screen FAB
    findViewById<FloatingActionButton>(R.id.split_screen_fab).setOnClickListener {
        launchSplitScreenMode()
    }
} 