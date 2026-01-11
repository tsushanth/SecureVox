import SwiftUI
import SwiftData
import AVFoundation

/// Detail view for viewing transcript, playback, and managing a recording
struct RecordingDetailView: View {

    // MARK: - Properties

    @StateObject private var viewModel: RecordingDetailViewModel

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    // MARK: - State

    @State private var showingRenameAlert = false
    @State private var showingDeleteConfirmation = false
    @State private var showingCancelConfirmation = false
    @State private var showingExportSheet = false
    @State private var showingImproveSheet = false
    @State private var newTitle = ""
    @AppStorage("transcriptViewMode") private var showParagraphView = false

    // MARK: - Initialization

    init(recording: Recording, modelContext: ModelContext) {
        _viewModel = StateObject(wrappedValue: RecordingDetailViewModel(
            recording: recording,
            modelContext: modelContext
        ))
    }

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Main content
            contentView

            // Bottom playback bar (if audio available)
            if viewModel.hasAudio {
                Divider()
                PlaybackBar(
                    isPlaying: viewModel.isPlaying,
                    currentTime: viewModel.currentTime,
                    duration: viewModel.recording.duration,
                    onPlayPause: { viewModel.togglePlayback() },
                    onSeek: { viewModel.seek(to: $0) },
                    onSkipBackward: { viewModel.skipBackward() },
                    onSkipForward: { viewModel.skipForward() },
                    playbackSpeed: viewModel.playbackSpeed,
                    onSpeedChange: { viewModel.playbackSpeed = $0 }
                )
            }
        }
        .navigationTitle(viewModel.recording.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .alert("Rename Recording", isPresented: $showingRenameAlert) {
            TextField("Title", text: $newTitle)
            Button("Cancel", role: .cancel) { }
            Button("Save") {
                viewModel.updateTitle(newTitle)
            }
        }
        .confirmationDialog(
            "Delete this recording?",
            isPresented: $showingDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Recording", role: .destructive) {
                print("[DELETE] Confirmation dialog - Delete button pressed")
                viewModel.deleteRecording()
                print("[DELETE] deleteRecording() called, now dismissing")
                dismiss()
            }
            Button("Cancel", role: .cancel) {
                print("[DELETE] Confirmation dialog - Cancel pressed")
            }
        }
        .confirmationDialog(
            "Cancel transcription?",
            isPresented: $showingCancelConfirmation,
            titleVisibility: .visible
        ) {
            Button("Cancel Transcription", role: .destructive) {
                viewModel.cancelTranscription()
            }
            Button("Continue", role: .cancel) { }
        }
        .alert("Error", isPresented: .constant(viewModel.errorMessage != nil)) {
            Button("OK") {
                viewModel.errorMessage = nil
            }
        } message: {
            if let error = viewModel.errorMessage {
                Text(error)
            }
        }
        .sheet(isPresented: $showingExportSheet) {
            ExportOptionsSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $showingImproveSheet) {
            ImproveTranscriptionSheet(viewModel: viewModel)
        }
        .sheet(item: $viewModel.shareURL) { url in
            ShareSheet(items: [url], onDismiss: {
                viewModel.clearShareURL()
            })
        }
        .sheet(isPresented: $viewModel.showRatingPrompt) {
            RatingPromptView(isPresented: $viewModel.showRatingPrompt)
                .presentationDetents([.height(340)])
        }
        .onDisappear {
            viewModel.stopPlayback()
        }
    }

    // MARK: - Content Views

    @ViewBuilder
    private var contentView: some View {
        switch viewModel.recording.status {
        case .pending:
            pendingView

        case .processing:
            processingView

        case .completed:
            transcriptView

        case .failed:
            failedView
        }
    }

    // MARK: - Pending View

    private var pendingView: some View {
        ContentUnavailableView {
            Label("Ready to Transcribe", systemImage: "text.badge.plus")
        } description: {
            Text("Tap the button below to start offline transcription using Whisper.")
        } actions: {
            Button {
                viewModel.startTranscription()
            } label: {
                Label("Start Transcription", systemImage: "waveform")
            }
            .buttonStyle(.borderedProminent)
            .disabled(!viewModel.canStartTranscription)
        }
    }

    // MARK: - Processing View (with live updates)

    private var processingView: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Show loading state or progress
                if viewModel.isLoadingModel {
                    modelLoadingSection
                        .padding(.top, 32)
                } else {
                    transcriptionProgressSection
                        .padding(.top, 32)
                }

                // Live Transcript Preview
                if !viewModel.transcriptText.isEmpty {
                    liveTranscriptSection
                }

                Spacer(minLength: 100)
            }
            .padding(.horizontal)
        }
    }

    private var modelLoadingSection: some View {
        VStack(spacing: 20) {
            ProgressView()
                .scaleEffect(1.5)
                .padding()

            Text("Loading Transcription Model")
                .font(.headline)

            Text(viewModel.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Text("This may take a moment on first use...")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(32)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var transcriptionProgressSection: some View {
        VStack(spacing: 16) {
            // Engine indicator badge
            HStack(spacing: 6) {
                Image(systemName: viewModel.transcriptionEngine.icon)
                    .font(.caption)
                Text(viewModel.transcriptionEngine.displayName)
                    .font(.caption)
                    .fontWeight(.medium)
                if viewModel.isUsingFallback {
                    Text("(Fallback)")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(viewModel.isUsingFallback ? Color.orange.opacity(0.15) : Color.blue.opacity(0.15))
            )

            // Animated waveform indicator
            HStack(spacing: 4) {
                ForEach(0..<5, id: \.self) { index in
                    WaveformBar(index: index, isAnimating: viewModel.isTranscribing)
                }
            }
            .frame(height: 40)

            // Progress bar
            VStack(spacing: 8) {
                ProgressView(value: viewModel.progress) {
                    Text("Transcribing")
                        .font(.headline)
                } currentValueLabel: {
                    Text(viewModel.progressPercentage)
                        .font(.title2)
                        .fontWeight(.semibold)
                        .monospacedDigit()
                }
                .progressViewStyle(.linear)
                .tint(viewModel.isUsingFallback ? .orange : .blue)
            }
            .padding(.horizontal, 20)

            // Status message
            Text(viewModel.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            // Cancel button
            Button(role: .destructive) {
                showingCancelConfirmation = true
            } label: {
                Label("Cancel", systemImage: "xmark.circle")
                    .font(.subheadline)
            }
            .buttonStyle(.bordered)
            .padding(.top, 8)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var liveTranscriptSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Live Preview", systemImage: "text.bubble")
                    .font(.headline)
                    .foregroundStyle(.secondary)

                Spacer()

                // Pulsing indicator
                Circle()
                    .fill(.green)
                    .frame(width: 8, height: 8)
                    .modifier(PulsingModifier())
            }

            Text(viewModel.transcriptText)
                .font(.body)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(Color(.tertiarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .transition(.opacity.combined(with: .move(edge: .bottom)))
        .animation(.easeInOut, value: viewModel.transcriptText.isEmpty)
    }

    // MARK: - Transcript View (completed)

    private var transcriptView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    // Apple Speech fallback notice
                    if viewModel.recording.usedAppleSpeechFallback {
                        appleSpeechFallbackBanner
                    }

                    // Model info and re-transcribe option
                    if viewModel.recording.canRetranscribe {
                        retranscribeBanner
                    }

                    // View mode toggle
                    if !viewModel.sortedSegments.isEmpty && !viewModel.transcriptText.isEmpty {
                        viewModeToggle
                    }

                    if viewModel.transcriptText.isEmpty {
                        ContentUnavailableView {
                            Label("No Transcript", systemImage: "text.quote")
                        } description: {
                            Text("The transcript will appear here after processing.")
                        }
                        .padding(.top, 100)
                    } else {
                        if showParagraphView {
                            // Paragraph view with highlighted active segment
                            paragraphTranscriptView
                        } else {
                            // List view with timestamps
                            listTranscriptView
                        }
                    }
                }
            }
            .onChange(of: viewModel.activeSegment?.id) { _, newSegmentID in
                if let segmentID = newSegmentID, !showParagraphView {
                    withAnimation(.easeInOut(duration: 0.3)) {
                        proxy.scrollTo(segmentID, anchor: .center)
                    }
                }
            }
        }
    }

    // MARK: - View Mode Toggle

    private var viewModeToggle: some View {
        HStack {
            Spacer()
            Picker("View Mode", selection: $showParagraphView) {
                Image(systemName: "list.bullet")
                    .tag(false)
                Image(systemName: "text.alignleft")
                    .tag(true)
            }
            .pickerStyle(.segmented)
            .frame(width: 100)
            .padding(.trailing)
            .padding(.vertical, 8)
        }
        .background(Color(.systemBackground))
    }

    // MARK: - List Transcript View

    private var listTranscriptView: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            // Full text view
            if viewModel.sortedSegments.isEmpty {
                Text(viewModel.transcriptText)
                    .font(.body)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // Segmented view with timestamps
                let currentPlaybackTime = viewModel.currentTime
                ForEach(viewModel.sortedSegments) { segment in
                    let isActive = currentPlaybackTime >= segment.startTime && currentPlaybackTime < segment.endTime
                    SegmentRowView(
                        segment: segment,
                        isActive: isActive,
                        onTap: {
                            viewModel.seekToSegment(segment)
                        }
                    )
                    .id(segment.id)
                }
            }
        }
    }

    // MARK: - Paragraph Transcript View

    private var paragraphTranscriptView: some View {
        let currentPlaybackTime = viewModel.currentTime

        return VStack(alignment: .leading, spacing: 0) {
            // Build attributed text with highlighting
            Text(buildHighlightedTranscript(currentTime: currentPlaybackTime))
                .font(.body)
                .lineSpacing(6)
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
    }

    /// Builds an AttributedString with the currently playing segment highlighted
    private func buildHighlightedTranscript(currentTime: TimeInterval) -> AttributedString {
        var result = AttributedString()

        let segments = viewModel.sortedSegments
        if segments.isEmpty {
            result = AttributedString(viewModel.transcriptText)
            return result
        }

        for (index, segment) in segments.enumerated() {
            let isActive = currentTime >= segment.startTime && currentTime < segment.endTime

            var segmentText = AttributedString(segment.text)

            if isActive {
                segmentText.backgroundColor = .blue.opacity(0.2)
                segmentText.foregroundColor = .primary
            }

            result.append(segmentText)

            // Add space between segments (but not after the last one)
            if index < segments.count - 1 {
                result.append(AttributedString(" "))
            }
        }

        return result
    }

    // MARK: - Apple Speech Fallback Banner

    private var appleSpeechFallbackBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "info.circle.fill")
                .font(.title3)
                .foregroundStyle(.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Transcribed with Apple Speech")
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundStyle(.primary)

                Text("Whisper AI provides better accuracy on iPhone 12 and newer devices.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(Color.blue.opacity(0.1))
    }

    // MARK: - Re-transcribe Banner

    private var retranscribeBanner: some View {
        Button {
            showingImproveSheet = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.title3)
                    .foregroundStyle(.blue)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Re-transcribe")
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundStyle(.primary)

                    if let currentModel = viewModel.recording.transcriptionModelDisplayName {
                        Text("Currently: \(currentModel) model")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Try a different model")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding()
            .background(Color.blue.opacity(0.1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Failed View

    private var failedView: some View {
        ContentUnavailableView {
            Label("Transcription Failed", systemImage: "exclamationmark.triangle")
        } description: {
            VStack(spacing: 8) {
                Text("An error occurred during transcription.")
                if let error = viewModel.recording.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } actions: {
            Button {
                viewModel.retryTranscription()
            } label: {
                Label("Retry", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    newTitle = viewModel.recording.title
                    showingRenameAlert = true
                } label: {
                    Label("Rename", systemImage: "character.cursor.ibeam")
                }

                if viewModel.canStartTranscription {
                    Button {
                        viewModel.startTranscription()
                    } label: {
                        Label("Start Transcription", systemImage: "waveform")
                    }
                }

                if viewModel.isTranscribing {
                    Button(role: .destructive) {
                        showingCancelConfirmation = true
                    } label: {
                        Label("Cancel Transcription", systemImage: "xmark.circle")
                    }
                }

                // Export options
                if viewModel.canExport {
                    Divider()

                    Menu {
                        Button {
                            viewModel.prepareShareURL(format: .txt)
                        } label: {
                            Label("Export TXT", systemImage: "doc.text")
                        }

                        Button {
                            viewModel.prepareShareURL(format: .srt)
                        } label: {
                            Label("Export SRT", systemImage: "captions.bubble")
                        }

                        Button {
                            viewModel.prepareShareURL(format: .vtt)
                        } label: {
                            Label("Export VTT", systemImage: "play.rectangle")
                        }
                    } label: {
                        Label("Export Transcript", systemImage: "square.and.arrow.up")
                    }
                }

                Divider()

                Button(role: .destructive) {
                    print("[DELETE] Menu - Delete Recording tapped, showing confirmation")
                    showingDeleteConfirmation = true
                } label: {
                    Label("Delete Recording", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }
}

// MARK: - Segment Row View

private struct SegmentRowView: View {
    let segment: TranscriptSegment
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 12) {
                // Timestamp
                Text(segment.formattedStartTime)
                    .font(.caption)
                    .fontWeight(.medium)
                    .monospacedDigit()
                    .foregroundStyle(isActive ? .blue : .secondary)
                    .frame(width: 50, alignment: .leading)

                // Text
                Text(segment.text)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
            .background(isActive ? Color.blue.opacity(0.1) : Color.clear)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Waveform Bar

private struct WaveformBar: View {
    let index: Int
    let isAnimating: Bool

    @State private var height: CGFloat = 10

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(Color.blue)
            .frame(width: 4, height: height)
            .onAppear {
                if isAnimating {
                    startAnimation()
                }
            }
            .onChange(of: isAnimating) { _, newValue in
                if newValue {
                    startAnimation()
                } else {
                    height = 10
                }
            }
    }

    private func startAnimation() {
        let delay = Double(index) * 0.1
        withAnimation(
            .easeInOut(duration: 0.4)
            .repeatForever(autoreverses: true)
            .delay(delay)
        ) {
            height = CGFloat.random(in: 15...35)
        }
    }
}

// MARK: - Pulsing Modifier

private struct PulsingModifier: ViewModifier {
    @State private var isPulsing = false

    func body(content: Content) -> some View {
        content
            .opacity(isPulsing ? 0.4 : 1.0)
            .onAppear {
                withAnimation(
                    .easeInOut(duration: 1.0)
                    .repeatForever(autoreverses: true)
                ) {
                    isPulsing = true
                }
            }
    }
}

// MARK: - Export Options Sheet

private struct ExportOptionsSheet: View {
    @ObservedObject var viewModel: RecordingDetailViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ExportFormat.allCases) { format in
                        Button {
                            viewModel.prepareShareURL(format: format)
                            dismiss()
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(format.displayName)
                                        .font(.body)
                                        .foregroundStyle(.primary)

                                    Text(format.formatDescription)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()

                                Image(systemName: "square.and.arrow.up")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                } header: {
                    Text("Choose Format")
                } footer: {
                    Text("The transcript will be exported and ready to share.")
                }
            }
            .navigationTitle("Export Transcript")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Re-transcribe Sheet

private struct ImproveTranscriptionSheet: View {
    @ObservedObject var viewModel: RecordingDetailViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if CoreMLTranscriber.hasNeuralEngineSupport {
                        Text("Choose quality level for re-transcription. Higher accuracy takes longer but produces better results.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Choose quality level. Fast is recommended for this device. Higher quality options may be slow without GPU acceleration.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    modelOption(
                        model: .tiny,
                        name: "Fast",
                        subtitle: "Quick results",
                        size: "Bundled",
                        speed: "Fastest",
                        ram: "~200 MB"
                    )

                    modelOption(
                        model: .base,
                        name: "Balanced",
                        subtitle: "2x slower",
                        size: "~140 MB",
                        speed: "Moderate",
                        ram: "~400 MB"
                    )

                    modelOption(
                        model: .small,
                        name: "Accurate",
                        subtitle: "5x slower",
                        size: "~460 MB",
                        speed: "Slower",
                        ram: "~1 GB"
                    )
                } header: {
                    Text("Choose Quality")
                } footer: {
                    Text("This will replace the current transcription.")
                }
            }
            .navigationTitle("Re-transcribe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func modelOption(
        model: CoreMLTranscriber.WhisperModel,
        name: String,
        subtitle: String,
        size: String,
        speed: String,
        ram: String
    ) -> some View {
        let currentModel = viewModel.recording.transcriptionModel
        let isCurrentModel = currentModel == model.rawValue

        return Button {
            dismiss()
            viewModel.improveTranscription(with: model)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(name)
                            .font(.body)
                            .fontWeight(.medium)
                            .foregroundStyle(.primary)

                        Text("(\(subtitle))")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        if isCurrentModel {
                            Text("Current")
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.blue.opacity(0.2))
                                .foregroundStyle(.blue)
                                .clipShape(Capsule())
                        }
                    }

                    HStack(spacing: 12) {
                        Text(size)
                        Text("·")
                        Text(speed)
                        Text("·")
                        Text(ram)
                    }
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                }

                Spacer()

                if !isCurrentModel {
                    Image(systemName: "arrow.right.circle.fill")
                        .foregroundStyle(.blue)
                }
            }
        }
        .disabled(isCurrentModel)
    }
}

// MARK: - URL Identifiable Extension

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        RecordingDetailView(
            recording: {
                let recording = Recording(
                    title: "Test Recording",
                    duration: 120,
                    sourceType: .microphone
                )
                return recording
            }(),
            modelContext: try! ModelContainer(for: Recording.self).mainContext
        )
    }
}
