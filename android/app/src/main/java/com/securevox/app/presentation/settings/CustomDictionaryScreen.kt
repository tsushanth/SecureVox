package com.securevox.app.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.securevox.app.data.CustomDictionaryService
import kotlinx.coroutines.launch

/**
 * Custom Dictionary Screen for managing user-defined words
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDictionaryScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val dictionaryService = remember { CustomDictionaryService.getInstance(context) }

    val words by dictionaryService.words.collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredWords = remember(words, searchQuery) {
        if (searchQuery.isBlank()) {
            words
        } else {
            words.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Custom Dictionary")
                        Text(
                            text = "${words.size} words",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add word")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import from clipboard") },
                                onClick = {
                                    showMoreMenu = false
                                    showImportDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export to clipboard") },
                                onClick = {
                                    showMoreMenu = false
                                    scope.launch {
                                        val exportText = dictionaryService.exportToString()
                                        if (exportText.isNotBlank()) {
                                            clipboardManager.setText(AnnotatedString(exportText))
                                            Toast.makeText(
                                                context,
                                                "Dictionary copied to clipboard",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Dictionary is empty",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Delete all", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMoreMenu = false
                                    showDeleteAllDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            if (words.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search words...") },
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
            }

            if (words.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No custom words yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Add words that are commonly misrecognized, like names, technical terms, or abbreviations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        OutlinedButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Word")
                        }
                    }
                }
            } else if (filteredWords.isEmpty()) {
                // No search results
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
                            text = "No words found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredWords, key = { it }) { word ->
                        WordCard(
                            word = word,
                            onDelete = {
                                scope.launch {
                                    dictionaryService.removeWord(word)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Add word dialog
    if (showAddDialog) {
        AddWordDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { word ->
                scope.launch {
                    val added = dictionaryService.addWord(word)
                    if (added) {
                        Toast.makeText(context, "Word added", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Word already exists", Toast.LENGTH_SHORT).show()
                    }
                }
                showAddDialog = false
            }
        )
    }

    // Import dialog
    if (showImportDialog) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text ->
                scope.launch {
                    val count = dictionaryService.importFromString(text)
                    Toast.makeText(
                        context,
                        if (count > 0) "$count words imported" else "No new words to import",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showImportDialog = false
            }
        )
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Words?") },
            text = { Text("This will remove all ${words.size} words from your custom dictionary. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dictionaryService.clearAll()
                            Toast.makeText(context, "Dictionary cleared", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun WordCard(
    word: String,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Word?") },
            text = { Text("Remove \"$word\" from your custom dictionary?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AddWordDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var word by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Word") },
        text = {
            Column {
                Text(
                    text = "Enter a word to add to your custom dictionary.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(word) },
                enabled = word.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        // Pre-fill with clipboard content if available
        clipboardManager.getText()?.let { clipText ->
            text = clipText.text
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Words") },
        text = {
            Column {
                Text(
                    text = "Paste words to import (one per line).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Words") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
