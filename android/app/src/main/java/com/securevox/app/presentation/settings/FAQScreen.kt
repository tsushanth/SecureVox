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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.securevox.app.R

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
                title = { Text(stringResource(R.string.faq_and_help)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
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
                placeholder = { Text(stringResource(R.string.search_faq)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
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
                            text = stringResource(R.string.no_results_found),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.try_different_search_term),
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
                                    text = stringResource(R.string.still_have_questions),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.contact_support),
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
                    contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
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
        answer = "SecureVox is a privacy-first voice transcription app that uses OpenAI's Whisper AI model to convert your voice recordings into text. Transcription runs on your device, and SecureVox does not upload your audio or transcripts.",
        category = FAQCategory.GENERAL
    ),
    FAQItem(
        question = "Is SecureVox free to use?",
        answer = "SecureVox is a one-time purchase on Google Play. There is no subscription, no in-app purchase and no ads.",
        category = FAQCategory.GENERAL
    ),
    FAQItem(
        question = "What languages are supported?",
        answer = "SecureVox offers 14 languages plus auto-detect. Accuracy depends on the language and the model: the default Tiny model is good for English only, Base also suits Spanish and Korean, and Small suits most major European languages, Japanese and Korean. Dutch, Polish, Chinese and Arabic stay less accurate even with Small, and Hindi is experimental.",
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
        answer = "There is no time limit on recording; it is limited by your free storage (about 2 MB per minute). Very long recordings take longer to transcribe. Speaker labels are only added to recordings up to about 30 minutes.",
        category = FAQCategory.RECORDING
    ),
    FAQItem(
        question = "Can I import audio files?",
        answer = "Yes! You can import audio files from your device. Tap the import button and select an audio file. SecureVox supports common audio formats like MP3, WAV and M4A, and it can transcribe the sound of video files too.",
        category = FAQCategory.RECORDING
    ),

    // Transcription
    FAQItem(
        question = "How accurate is the transcription?",
        answer = "Accuracy depends on the language and on the model you choose. Small is the most accurate and the slowest, Tiny is the fastest and makes the most mistakes. Clear audio with little background noise gives the best results.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "What are the different transcription models?",
        answer = "SecureVox offers three models:\n\n• Tiny (about 78 MB): fastest, good for English\n• Base (about 148 MB): a balance of speed and accuracy\n• Small (about 488 MB): most accurate, needs a phone with plenty of memory\n\nYou can download and switch models in Settings.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "Why is transcription slow on my device?",
        answer = "Transcription speed depends on your device's processing power. Newer devices with more powerful chips will transcribe faster. Try the Tiny model for quicker results, or record in shorter segments.",
        category = FAQCategory.TRANSCRIPTION
    ),
    FAQItem(
        question = "Can I edit the transcription?",
        answer = "Not yet: transcripts cannot be edited inside SecureVox. You can copy the text or export it as TXT, SRT or VTT and edit it in another app. To improve future transcriptions, add commonly misrecognized names and terms to the custom dictionary in Settings.",
        category = FAQCategory.TRANSCRIPTION
    ),

    // Privacy
    FAQItem(
        question = "Is my data secure?",
        answer = "SecureVox transcribes on your device and does not upload your audio or transcripts. Recordings are stored in the app on your phone. Android Auto Backup may copy app data to your Google account if you have backup turned on in Android settings. SecureVox has no account, no ads and no analytics SDK. Optional anonymous crash reports (app version, phone model, error type; never audio or text) are off unless you turn them on in Settings.",
        category = FAQCategory.PRIVACY
    ),
    FAQItem(
        question = "Does SecureVox need an internet connection?",
        answer = "Only once: the first time you use SecureVox it downloads the speech model (Tiny, about 78 MB). After that, recording and transcription work without a connection. Larger models (Base about 148 MB, Small about 488 MB) are optional downloads.",
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
        answer = "This can happen if your device runs low on memory, especially with larger models. Try:\n\n• Using the Tiny model, which uses the least memory\n• Closing other apps to free up memory\n• Recording shorter segments\n• Restarting your device",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "My recordings sound too quiet",
        answer = "Hold your phone closer to the person speaking and avoid covering the microphone with your hand or a case. SecureVox records at the volume your phone's microphone provides and has no gain control.",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "The transcription has many errors",
        answer = "Try these tips for better accuracy:\n\n• Use a larger model (Base or Small) in Settings\n• Record in a quiet environment\n• Speak clearly and at a moderate pace\n• Select the correct language instead of auto-detect\n• Add commonly misrecognized words to your custom dictionary",
        category = FAQCategory.TROUBLESHOOTING
    ),
    FAQItem(
        question = "How do I delete all my data?",
        answer = "Delete a recording with the bin icon next to it in the list; this permanently removes its audio and transcript and cannot be undone. There is no delete-all button yet. Uninstalling SecureVox removes everything it stored on your phone.",
        category = FAQCategory.TROUBLESHOOTING
    )
)
