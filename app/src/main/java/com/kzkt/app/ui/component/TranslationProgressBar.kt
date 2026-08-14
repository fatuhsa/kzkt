package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kzkt.app.ui.MainViewModel

/** Overall translation progress bar with a "done / total" counter. */
@Composable
fun TranslationProgressBar(viewModel: MainViewModel) {
    val progress by remember { derivedStateOf { viewModel.translationProgress.value } }
    val done by remember { derivedStateOf { viewModel.translationDone.value } }
    val total by remember { derivedStateOf { viewModel.translationTotal.value } }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$done / $total",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
