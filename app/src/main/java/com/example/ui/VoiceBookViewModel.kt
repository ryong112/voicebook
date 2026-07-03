package com.example.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.data.BookRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class VoiceBookViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val repository: BookRepository
    val books: StateFlow<List<Book>>

    // UI state variables
    var currentTabScan by mutableStateOf(true) // true for Scan Mode, false for Library Mode
    var scannedText by mutableStateOf("")
    var bookTitleInput by mutableStateOf("")
    var isOcrProcessing by mutableStateOf(false)
    
    // Select Book Folder System in Scan Mode
    var selectedBookForScan: Book? by mutableStateOf(null)
    var showCreateBookDialog by mutableStateOf(false)
    var newBookTitleInput by mutableStateOf("")

    // Auto-Bookmark & Resume Reading Dialog states
    var showResumeDialog by mutableStateOf(false)
    var pendingResumeBook: Book? by mutableStateOf(null)
    
    // Library tab states
    var selectedBook: Book? by mutableStateOf(null)
    var readerFontSize by mutableStateOf(22) // Default large readable size for low-vision users
    
    // TTS states
    private var tts: TextToSpeech? = null
    var isTtsReady by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var isDownloading by mutableStateOf(false)
    var speechRate by mutableStateOf(1.0f) // Speech rate between 0.5f and 2.0f
    
    // Toast / Banner state
    var toastMessage by mutableStateOf<String?>(null)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BookRepository(database.bookDao())
        books = repository.allBooks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        
        viewModelScope.launch {
            books.collect { list ->
                if (list.isNotEmpty()) {
                    if (selectedBookForScan == null) {
                        selectedBookForScan = list.first()
                    } else {
                        // Keep selectedBookForScan in sync if it gets updated in the database
                        val updated = list.find { it.id == selectedBookForScan?.id }
                        if (updated != null) {
                            selectedBookForScan = updated
                        }
                    }
                    if (selectedBook != null) {
                        val updated = list.find { it.id == selectedBook?.id }
                        if (updated != null) {
                            selectedBook = updated
                        }
                    }
                }
            }
        }
        
        // Initialize TextToSpeech
        tts = TextToSpeech(application, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to bilingual or default locale
                tts?.setLanguage(Locale.getDefault())
                Log.e("VoiceBookTTS", "Korean is not fully supported, fell back to default locale.")
            }
            
            // Set listener to monitor playback and download
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == "play_utterance") {
                        isPlaying = true
                    } else if (utteranceId != null && utteranceId.startsWith("sentence_index_")) {
                        isPlaying = true
                        val parts = utteranceId.substringAfter("sentence_index_").split("_")
                        if (parts.size >= 2) {
                            val index = parts[0].toIntOrNull() ?: 0
                            val bookId = parts[1].toLongOrNull() ?: 0L
                            updateBookBookmark(bookId, index)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "play_utterance") {
                        isPlaying = false
                    } else if (utteranceId != null && utteranceId.startsWith("sentence_index_")) {
                        val parts = utteranceId.substringAfter("sentence_index_").split("_")
                        if (parts.size >= 2) {
                            val index = parts[0].toIntOrNull() ?: 0
                            val bookId = parts[1].toLongOrNull() ?: 0L
                            viewModelScope.launch {
                                val currentBook = selectedBook
                                if (currentBook != null && currentBook.id == bookId) {
                                    val sentences = splitTextToSentences(currentBook.text)
                                    if (index >= sentences.size - 1) {
                                        // Finished reading! Reset bookmark to 0
                                        updateBookBookmark(bookId, 0)
                                        isPlaying = false
                                    }
                                }
                            }
                        }
                    } else if (utteranceId == "download_utterance") {
                        viewModelScope.launch {
                            finalizeAudioDownload()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isPlaying = false
                    isDownloading = false
                    showToast("음성 지원 중 오류가 발생했습니다.")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isPlaying = false
                    isDownloading = false
                    showToast("음성 오류 코드: $errorCode")
                }
            })
            
            isTtsReady = true
        } else {
            Log.e("VoiceBookTTS", "TTS Initialization failed.")
            showToast("TTS 음성 엔진 초기화에 실패했습니다.")
        }
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            toastMessage = message
        }
    }

    fun clearToast() {
        toastMessage = null
    }

    // --- OCR SCANNING METHOD ---
    fun processImageForOcr(context: Context, imageUri: Uri) {
        isOcrProcessing = true
        scannedText = ""
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(context, imageUri)
                // Korean recognizer handles Korean and Latin (English) simultaneously.
                val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        isOcrProcessing = false
                        val recognizedText = visionText.text
                        if (recognizedText.trim().isEmpty()) {
                            scannedText = "인식된 텍스트가 없습니다. 이미지를 다시 확인해주세요."
                            showToast("텍스트를 추출하지 못했습니다.")
                        } else {
                            scannedText = recognizedText
                            showToast("텍스트가 성공적으로 추출되었습니다!")
                        }
                    }
                    .addOnFailureListener { e ->
                        isOcrProcessing = false
                        scannedText = "오류 발생: ${e.localizedMessage}"
                        showToast("OCR 스캔 도중 오류가 발생했습니다.")
                    }
            } catch (e: Exception) {
                isOcrProcessing = false
                scannedText = "이미지 분석 오류: ${e.localizedMessage}"
                showToast("이미지를 로드하는 중 실패했습니다.")
            }
        }
    }

    // --- BOOK STORAGE ACTIONS ---
    fun splitTextToSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("(?<=[.!?\n])\\s+")
        return text.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun updateBookBookmark(bookId: Long, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookList = books.value
            val book = bookList.find { it.id == bookId }
            if (book != null) {
                val updatedBook = book.copy(bookmarkIndex = index)
                repository.updateBook(updatedBook)
                withContext(Dispatchers.Main) {
                    if (selectedBook?.id == bookId) {
                        selectedBook = updatedBook
                    }
                    if (selectedBookForScan?.id == bookId) {
                        selectedBookForScan = updatedBook
                    }
                }
            }
        }
    }

    fun createNewBook(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            showToast("올바른 도서 제목을 입력해주세요.")
            return
        }
        viewModelScope.launch {
            val existing = repository.getBookByTitle(trimmed)
            if (existing != null) {
                showToast("이미 존재하는 도서 제목입니다.")
                selectedBookForScan = existing
            } else {
                repository.saveOrAppendBook(trimmed, "") // Create with empty text
                val created = repository.getBookByTitle(trimmed)
                if (created != null) {
                    selectedBookForScan = created
                    showToast("새 도서 '$trimmed'가 등록되었습니다.")
                }
            }
            newBookTitleInput = ""
            showCreateBookDialog = false
        }
    }

    fun saveOrAppendBookToSelectedFolder() {
        val book = selectedBookForScan
        val text = scannedText.trim()
        
        if (book == null) {
            showToast("먼저 도서 폴더를 선택하거나 생성해 주세요.")
            return
        }
        if (text.isEmpty() || text.startsWith("인식된 텍스트가") || text.startsWith("오류 발생")) {
            showToast("저장할 올바른 텍스트가 없습니다.")
            return
        }

        viewModelScope.launch {
            repository.saveOrAppendBook(book.title, text)
            showToast("'${book.title}' 도서에 텍스트가 안전하게 저장되었습니다.")
            // Retrieve updated book to update selection and UI
            val updated = repository.getBookByTitle(book.title)
            if (updated != null) {
                selectedBookForScan = updated
                if (selectedBook?.id == updated.id) {
                    selectedBook = updated
                }
            }
            scannedText = ""
        }
    }

    fun saveOrAppendBook() {
        val title = bookTitleInput.trim()
        val text = scannedText.trim()
        
        if (title.isEmpty()) {
            showToast("도서 제목을 입력해주세요.")
            return
        }
        if (text.isEmpty() || text.startsWith("인식된 텍스트가") || text.startsWith("오류 발생")) {
            showToast("저장할 올바른 텍스트가 없습니다.")
            return
        }

        viewModelScope.launch {
            repository.saveOrAppendBook(title, text)
            showToast("'$title' 도서에 텍스트가 안전하게 저장되었습니다.")
            // Reset scan fields
            bookTitleInput = ""
            scannedText = ""
        }
    }

    fun deleteCurrentBook() {
        val book = selectedBook ?: return
        viewModelScope.launch {
            repository.deleteBook(book.id)
            showToast("도서 '${book.title}'이(가) 삭제되었습니다.")
            selectedBook = null
            stopTts()
        }
    }

    // --- TTS PLAYBACK ACTIONS ---
    fun playTts(book: Book) {
        if (!isTtsReady || tts == null) {
            showToast("음성 엔진이 준비되지 않았습니다.")
            return
        }
        if (book.text.trim().isEmpty()) {
            showToast("읽을 텍스트가 존재하지 않습니다.")
            return
        }

        if (isPlaying) {
            stopTts()
        } else {
            if (book.bookmarkIndex > 0) {
                pendingResumeBook = book
                showResumeDialog = true
            } else {
                playTtsFromBookmark(book, 0)
            }
        }
    }

    fun playTtsFromBookmark(book: Book, startIndex: Int) {
        if (!isTtsReady || tts == null) {
            showToast("음성 엔진이 준비되지 않았습니다.")
            return
        }
        
        val sentences = splitTextToSentences(book.text)
        if (sentences.isEmpty()) {
            showToast("읽을 텍스트가 존재하지 않습니다.")
            return
        }

        if (isPlaying) {
            stopTts()
        }
        
        tts?.setSpeechRate(speechRate)
        isPlaying = true
        
        // Queue all sentences from startIndex to the end
        val bookId = book.id
        for (i in startIndex until sentences.size) {
            val sParams = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sentence_index_${i}_${bookId}")
            }
            tts?.speak(sentences[i], TextToSpeech.QUEUE_ADD, sParams, "sentence_index_${i}_${bookId}")
        }
    }

    fun playTts(text: String) {
        if (!isTtsReady || tts == null) {
            showToast("음성 엔진이 준비되지 않았습니다.")
            return
        }
        
        if (text.trim().isEmpty()) {
            showToast("읽을 텍스트가 존재하지 않습니다.")
            return
        }

        if (isPlaying) {
            stopTts()
        } else {
            tts?.setSpeechRate(speechRate)
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "play_utterance")
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "play_utterance")
            isPlaying = true
        }
    }

    fun stopTts() {
        tts?.stop()
        isPlaying = false
    }

    // --- AUDIO DOWNLOAD (TTS TO MP3) ---
    private var tempTtsFile: File? = null
    private var downloadTitle = ""

    fun downloadTtsAudio(context: Context, bookTitle: String, text: String) {
        if (!isTtsReady || tts == null) {
            showToast("음성 엔진이 준비되지 않았습니다.")
            return
        }
        if (text.trim().isEmpty()) {
            showToast("저장할 도서 텍스트가 없습니다.")
            return
        }
        if (isDownloading) {
            showToast("현재 다른 오디오 파일 다운로드가 진행 중입니다.")
            return
        }

        isDownloading = true
        downloadTitle = bookTitle.ifEmpty { "목소리_책" }.replace(Regex("[^a-zA-Z0-9가-힣_]"), "_")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save to app's cache dir temporarily
                val tempFile = File(context.cacheDir, "temp_tts_synthesis.mp3")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                tempFile.createNewFile()
                tempTtsFile = tempFile
                
                val params = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "download_utterance")
                }
                
                tts?.setSpeechRate(speechRate)
                // Synthesize TTS directly to the cache file
                val result = tts?.synthesizeToFile(text, params, tempFile, "download_utterance")
                if (result == TextToSpeech.ERROR) {
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        showToast("음성 파일 변환에 실패했습니다.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    showToast("오디오 준비 중 오류: ${e.localizedMessage}")
                }
            }
        }
    }

    private suspend fun finalizeAudioDownload() {
        val tempFile = tempTtsFile ?: return
        if (!tempFile.exists() || tempFile.length() <= 0) {
            withContext(Dispatchers.Main) {
                isDownloading = false
                showToast("음성 녹음 데이터 생성에 오류가 있었습니다.")
            }
            return
        }

        val app = getApplication<Application>()
        val fileName = "${downloadTitle}_${System.currentTimeMillis() / 1000}.mp3"

        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = app.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceBook")
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outStream ->
                            FileInputStream(tempFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isDownloading = false
                            showToast("내 다운로드/VoiceBook 폴더에 MP3가 저장되었습니다!")
                        }
                    } else {
                        throw Exception("MediaStore URI 생성 실패")
                    }
                } else {
                    // Fallback for older SDKs using public Downloads directory
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val voiceBookDir = File(downloadsDir, "VoiceBook")
                    if (!voiceBookDir.exists()) {
                        voiceBookDir.mkdirs()
                    }
                    val targetFile = File(voiceBookDir, fileName)
                    FileOutputStream(targetFile).use { outStream ->
                        FileInputStream(tempFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        showToast("다운로드/VoiceBook 폴더에 MP3 파일이 저장되었습니다!")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    showToast("다운로드 폴더에 저장하지 못했습니다: ${e.localizedMessage}")
                }
            } finally {
                // Clean up cache file
                try {
                    tempFile.delete()
                } catch (e: Exception) {
                    // Ignore
                }
                tempTtsFile = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
        tts = null
    }
}
