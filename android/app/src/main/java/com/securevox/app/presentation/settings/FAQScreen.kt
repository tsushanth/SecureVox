package com.securevox.app.presentation.settings

import com.securevox.app.util.mirrorInRtl
import androidx.annotation.StringRes
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
 * FAQ item as shown on screen (already in the app language).
 */
data class FAQItem(
    val question: String,
    val answer: String,
    val category: FAQCategory
)

/** Unresolved FAQ entry: string resource ids, resolved in the current app language when the screen is drawn. */
private data class FAQSpec(
    @StringRes val question: Int,
    @StringRes val answer: Int,
    val category: FAQCategory
)

/**
 * FAQ Categories
 */
enum class FAQCategory(@StringRes val titleRes: Int) {
    GENERAL(R.string.faq_cat_general),
    RECORDING(R.string.faq_cat_recording),
    TRANSCRIPTION(R.string.faq_cat_transcription),
    PRIVACY(R.string.faq_cat_privacy),
    TROUBLESHOOTING(R.string.faq_cat_troubleshooting)
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

    val faqItems = faqSpecs.map { FAQItem(stringResource(it.question), stringResource(it.answer), it.category) }

    val filteredItems = remember(searchQuery, faqItems) {
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
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.mirrorInRtl())
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
                                text = stringResource(category.titleRes),
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
 * All FAQ items, in display order (grouped by category on screen).
 */
private val faqSpecs: List<FAQSpec> = listOf(
    FAQSpec(R.string.faq_01_q, R.string.faq_01_a, FAQCategory.GENERAL),
    FAQSpec(R.string.faq_02_q, R.string.faq_02_a, FAQCategory.GENERAL),
    FAQSpec(R.string.faq_03_q, R.string.faq_03_a, FAQCategory.GENERAL),
    FAQSpec(R.string.faq_04_q, R.string.faq_04_a, FAQCategory.RECORDING),
    FAQSpec(R.string.faq_05_q, R.string.faq_05_a, FAQCategory.RECORDING),
    FAQSpec(R.string.faq_06_q, R.string.faq_06_a, FAQCategory.RECORDING),
    FAQSpec(R.string.faq_07_q, R.string.faq_07_a, FAQCategory.TRANSCRIPTION),
    FAQSpec(R.string.faq_08_q, R.string.faq_08_a, FAQCategory.TRANSCRIPTION),
    FAQSpec(R.string.faq_09_q, R.string.faq_09_a, FAQCategory.TRANSCRIPTION),
    FAQSpec(R.string.faq_10_q, R.string.faq_10_a, FAQCategory.TRANSCRIPTION),
    FAQSpec(R.string.faq_11_q, R.string.faq_11_a, FAQCategory.PRIVACY),
    FAQSpec(R.string.faq_12_q, R.string.faq_12_a, FAQCategory.PRIVACY),
    FAQSpec(R.string.faq_13_q, R.string.faq_13_a, FAQCategory.PRIVACY),
    FAQSpec(R.string.faq_14_q, R.string.faq_14_a, FAQCategory.TROUBLESHOOTING),
    FAQSpec(R.string.faq_15_q, R.string.faq_15_a, FAQCategory.TROUBLESHOOTING),
    FAQSpec(R.string.faq_16_q, R.string.faq_16_a, FAQCategory.TROUBLESHOOTING),
    FAQSpec(R.string.faq_17_q, R.string.faq_17_a, FAQCategory.TROUBLESHOOTING)
)
