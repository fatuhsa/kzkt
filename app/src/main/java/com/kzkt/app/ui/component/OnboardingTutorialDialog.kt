package com.kzkt.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class OnboardingStep(
    val title: String,
    val subtitle: String,
    val badgeText: String,
    val headerIcon: ImageVector,
    val points: List<OnboardingPoint>,
    val actionText: String? = null,
    val onAction: (() -> Unit)? = null,
)

private data class OnboardingPoint(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val isCrucial: Boolean = false,
)

@Composable
fun OnboardingTutorialDialog(
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTranslate: () -> Unit,
) {
    var currentStep by remember { mutableIntStateOf(0) }

    val steps =
        remember {
            listOf(
                OnboardingStep(
                    title = "Welcome to KZKT",
                    subtitle = "On-device AI-powered automatic manga and comic translator.",
                    badgeText = "Workflow",
                    headerIcon = Icons.Outlined.Translate,
                    points =
                        listOf(
                            OnboardingPoint(
                                icon = Icons.Default.AutoAwesome,
                                title = "1. Bubble Detection (YOLO ONNX)",
                                description =
                                    "Automatically detects manga speech bubble coordinates " +
                                        "on-device without needing an internet connection.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.Translate,
                                title = "2. Contextual Translation (AI LLM)",
                                description =
                                    "Translates manga dialog into your preferred language " +
                                        "while preserving storytelling context.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.Edit,
                                title = "3. Inpainting & Clean Rendering",
                                description =
                                    "Erases original text strokes cleanly and fits translated text " +
                                        "neatly into bubble bounds.",
                            ),
                        ),
                ),
                OnboardingStep(
                    title = "Setup AI API Key",
                    subtitle = "Required first step so the AI engine can process and translate comic text.",
                    badgeText = "Crucial / Required",
                    headerIcon = Icons.Outlined.Key,
                    points =
                        listOf(
                            OnboardingPoint(
                                icon = Icons.Default.Key,
                                title = "Google Gemini (Free & Fast)",
                                description =
                                    "Get a free API Key at Google AI Studio (aistudio.google.com) " +
                                        "to start translating immediately.",
                                isCrucial = true,
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.Settings,
                                title = "Extensive AI Providers",
                                description =
                                    "Supports Google Gemini, OpenAI, Claude Anthropic, OpenRouter, " +
                                        "DeepSeek, Groq, and Custom Endpoints.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Outlined.Settings,
                                title = "Settings Menu Navigation",
                                description = "Go to Settings tab → AI & Provider section → paste your API Key.",
                            ),
                        ),
                    actionText = "Open API Settings Now",
                    onAction = onNavigateToSettings,
                ),
                OnboardingStep(
                    title = "Import & Translate",
                    subtitle = "Supports all your favorite comic and manga formats.",
                    badgeText = "Translate Tab",
                    headerIcon = Icons.Outlined.Folder,
                    points =
                        listOf(
                            OnboardingPoint(
                                icon = Icons.Default.Image,
                                title = "Supported File Formats",
                                description =
                                    "Supports single images (JPG/PNG/WebP), entire manga folders, " +
                                        "PDF files, and ZIP/CBZ archives.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.Translate,
                                title = "Quick Config Badges",
                                description =
                                    "Tap the target language (→ Indonesian) or provider badge " +
                                        "on the Translate screen to switch preferences instantly.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.AutoAwesome,
                                title = "Translation Logs",
                                description =
                                    "Monitor OCR, inpainting, and translation progress live " +
                                        "via the Translation Logs button at the bottom.",
                            ),
                        ),
                ),
                OnboardingStep(
                    title = "Reader & Touch-up Editor",
                    subtitle = "Enjoy your translated manga and customize typography.",
                    badgeText = "History Tab",
                    headerIcon = Icons.AutoMirrored.Outlined.MenuBook,
                    points =
                        listOf(
                            OnboardingPoint(
                                icon = Icons.Default.History,
                                title = "History Tab & In-App Reader",
                                description =
                                    "Open past translations anytime in Page or continuous " +
                                        "Webtoon vertical scroll reading modes.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Default.Edit,
                                title = "Interactive Touch-up Editor",
                                description =
                                    "Touch speech bubbles directly on the image to edit translations, " +
                                        "pick custom fonts, or adjust text sizes.",
                            ),
                            OnboardingPoint(
                                icon = Icons.Outlined.Folder,
                                title = "Auto-Saved Outputs",
                                description = "All translated images and PDFs are automatically saved to your Download/KZKT folder.",
                            ),
                        ),
                    actionText = "Start Translating",
                    onAction = onNavigateToTranslate,
                ),
            )
        }

    val step = steps[currentStep]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
            ) {
                // ── Top Header Row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color =
                            if (currentStep == 1) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = step.headerIcon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint =
                                    if (currentStep == 1) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                            Text(
                                text = step.badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (currentStep == 1) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                        }
                    }

                    Text(
                        text = "${currentStep + 1} / ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Animated Content Area ──
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut(),
                            )
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut(),
                            )
                        }
                    },
                    label = "OnboardingStepContent",
                ) { targetStepIdx ->
                    val targetStep = steps[targetStepIdx]
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = targetStep.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = targetStep.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(16.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            targetStep.points.forEach { point ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color =
                                        if (point.isCrucial) {
                                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                    border =
                                        if (point.isCrucial) {
                                            androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                            )
                                        } else {
                                            null
                                        },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            imageVector = point.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp).padding(top = 2.dp),
                                            tint =
                                                if (point.isCrucial) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                        )
                                        Column {
                                            Text(
                                                text = point.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color =
                                                    if (point.isCrucial) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = point.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 18.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Optional direct action button (e.g. "Buka Pengaturan API Sekarang")
                        if (targetStep.actionText != null && targetStep.onAction != null) {
                            Spacer(Modifier.height(14.dp))
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    targetStep.onAction.invoke()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector =
                                        if (targetStepIdx == 1) {
                                            Icons.Default.Settings
                                        } else {
                                            Icons.Default.Translate
                                        },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = targetStep.actionText,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(16.dp))

                // ── Footer Navigation Row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Page indicator dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        steps.indices.forEach { index ->
                            val isSelected = index == currentStep
                            Box(
                                modifier =
                                    Modifier
                                        .height(8.dp)
                                        .width(if (isSelected) 22.dp else 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            },
                                        ),
                            )
                        }
                    }

                    // Navigation buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentStep > 0) {
                            OutlinedButton(
                                onClick = { currentStep-- },
                                shape = RoundedCornerShape(50),
                                contentPadding =
                                    androidx.compose.foundation.layout
                                        .PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Back")
                            }
                        } else {
                            TextButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Button(
                            onClick = {
                                if (currentStep < steps.size - 1) {
                                    currentStep++
                                } else {
                                    onDismiss()
                                    onNavigateToTranslate()
                                }
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(if (currentStep == steps.size - 1) "Get Started" else "Next")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (currentStep == steps.size - 1) Icons.Default.Check else Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
