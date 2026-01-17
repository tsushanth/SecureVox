package com.securevox.app.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * FAQ Item data class
 */
data class FAQItem(
    val question: String,
    val answer: String,
    val category: FAQCategory
)

/**
 * FAQ Categories matching iOS implementation
 */
enum class FAQCategory(val displayName: String) {
    GENERAL("General"),
    RECORDING("Recording"),
    TRANSCRIPTION("Transcription"),
    PRIVACY("Privacy & Security"),
    TROUBLESHOOTING("Troubleshooting")
}

/**
 * FAQ/Help Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FAQScreen(
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var expandedItems by remember { mutableStateOf(setOf<Int>()) }

    val faqItems = remember { getFAQItems() }

    val filteredItems = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            faqItems
        } else {
            faqItems.filter {
                it.question.contains(searchQuery, ignoreCase = true) ||
                it.answer.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val groupedItems = remember(filteredItems) {
        filteredItems.groupBy { it.category }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FAQ & Help") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search FAQ...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            if (filteredItems.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Try a different search term",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedItems.forEach { (category, items) ->
                        item {
                            Text(
                                text = category.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(items) { faqItem ->
                            val index = faqItems.indexOf(faqItem)
                            val isExpanded = expandedItems.contains(index)

                            FAQCard(
                                item = faqItem,
                                isExpanded = isExpanded,
                                onToggle = {
                                    expandedItems = if (isExpanded) {
                                        expandedItems - index
                                    } else {
                                        expandedItems + index
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    // Contact support section
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.HelpOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Still have questions?",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Contact us at support@securevox.app",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FAQCard(
    item: FAQItem,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.question,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = item.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Returns all FAQ items - matching iOS content
 */
private fun getFAQItems(): List<FAQItem> = listOf(
    // General
    FAQItem(
        question = "What is SecureVox?",
        answer = "SecureVox is a privacy-first voice transcription app that uses OpenAI's Whisper AI model to convert your voice recordings into text. All processing happens locally on your device - your audio never leaves your phone.",
        category = FAQCategory.GENERAL
    ),
    FAQItem(
        question = "Is SecureVox free to use?",
        answer = "Yes! SecureVox is completely free with no ads, no subscriptions, and no hidden costs. We believe privacy should be accessible to everyone.",
        category = FAQCategory.GENERAL
    ),
    FAQItem(
        question = "What languages are supported?",
        answer = "Whisper supports 99 languages including English, Spanish, French, German, Chinese, Japanese, Arabic, and many more. You can set a default language or use auto-detection for mixed-language content.",
        category = FAQCategory.GENERAL
    ),

    // Recording
    FAQItem(
        question = "How do I start a recording?",
        answer = "Tap the microphone button at the bottom of the screen to start recording. Tap the stop button when you're done. The recording will automatically be transcribed.",
        category = FAQCategory.RECORDING
    ),
    FAQItem(
        question = "Is there a time limit for recordings?",
        answer = "There is no artificial time limit. Recording duration is only limited by your device's available storage space. However, very long recordings may take longer to transcribe.",
        category = FAQCategory.RECORDING
    ),
    FAQItem(
        question = "Can I import audio files?",
        answer = "Yes! You can import audio files from your device. Tap the import button and select an audio file. SecureVox supports common formats like MP3, WAV, M4A, and more.",
        category = FAQCategory.RECORDING
    ),

    // Transcription
    FAQItem(
        question = "How accurate is the transcription?",
        answer = "Accuracy depends on the model you choose. The 'Accurate' model provides the best results but is slower. The 'Fast' model is quicker but may have more errors. Clear audio with minimal background noise produces the best results.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "What are the different transcription models?",
        answer = "SecureVox offers three models:\n\n• Fast: Quickest results, good for clear audio\n• Balanced: Good balance of speed and accuracy\n• Accurate: Best accuracy, recommended for important recordings\n\nYou can download additional models in Settings.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "Why is transcription slow on my device?",
        answer = "Transcription speed depends on your device's processing power. Newer devices with more powerful chips will transcribe faster. Try using the 'Fast' model for quicker results, or record in shorter segments.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "Can I edit the transcription?",
        answer = "Yes! Tap on any recording to view its details, then tap the edit button to make changes to the transcript. You can also add custom words to the dictionary to improve future transcriptions.",
        category = FAQCategory.TRANSCRIPTION
    ),

    // Privacy
    FAQItem(
        question = "Is my data secure?",
        answer = "Absolutely. SecureVox processes everything locally on your device. Your recordings and transcripts are stored only on your device and are never uploaded to any server. We don't collect any personal data or analytics.",
        category = FAQCategory.PRIVACY
    ),
    FAQItem(
        question = "Does SecureVox need an internet connection?",
        answer = "No! After the initial model download, SecureVox works completely offline. You can record and transcribe without any internet connection.",
        category = FAQCategory.PRIVACY
    ),
    FAQItem(
        question = "Where is my data stored?",
        answer = "All recordings and transcripts are stored locally on your device in the app's private storage area. Other apps cannot access this data. You can export or delete your data at any time.",
        category = FAQCategory.PRIVACY
    ),

    // Troubleshooting
    FAQItem(
        question = "The app crashed during transcription",
        answer = "This can happen if your device runs low on memory, especially with larger models. Try:\n\n• Using the 'Fast' model which uses less memory\n• Closing other apps to free up memory\n• Recording shorter segments\n• Restarting your device",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "My recordings sound too quiet",
        answer = "You can adjust the input gain in Settings > Audio. Increase the gain to boost quiet recordings. Also, try holding your device closer to the sound source when recording.",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "The transcription has many errors",
        answer = "Try these tips for better accuracy:\n\n• Use the 'Accurate' model\n• Record in a quiet environment\n• Speak clearly and at a moderate pace\n• Select the correct language instead of auto-detect\n• Add commonly misrecognized words to your custom dictionary",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "How do I delete all my data?",
        answer = "Go to Settings > Storage and tap 'Delete All Recordings'. This will permanently remove all recordings and transcripts. You can also delete individual recordings by swiping left on them in the list.",
        category = FAQCategory.TROUBLESHOOTING
    )
)
