import SwiftUI

/// Row component for displaying a transcript segment
struct SegmentRow: View {

    // MARK: - Properties

    let segment: TranscriptSegment
    let isActive: Bool
    let isEditing: Bool
    let onTap: () -> Void
    let onTextChange: (String) -> Void

    // MARK: - State

    @State private var editedText: String = ""
    @FocusState private var isFocused: Bool

    // MARK: - Body

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Timestamp
            Text(segment.formattedStartTime)
                .font(.caption)
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .frame(width: 50, alignment: .trailing)

            // Content
            VStack(alignment: .leading, spacing: 4) {
                if isEditing {
                    editableContent
                } else {
                    readOnlyContent
                }
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(isActive ? Color.accentColor.opacity(0.1) : Color.clear)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture {
            if !isEditing {
                onTap()
            }
        }
        .onAppear {
            editedText = segment.text
        }
        .onChange(of: segment.text) { _, newValue in
            editedText = newValue
        }
    }

    // MARK: - Subviews

    private var readOnlyContent: some View {
        Text(segment.text)
            .font(.body)
            .foregroundStyle(isActive ? .primary : .secondary)
    }

    private var editableContent: some View {
        TextField("Segment text", text: $editedText, axis: .vertical)
            .font(.body)
            .textFieldStyle(.roundedBorder)
            .focused($isFocused)
            .onChange(of: editedText) { _, newValue in
                onTextChange(newValue)
            }
            .onChange(of: isEditing) { _, editing in
                if !editing {
                    isFocused = false
                }
            }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 0) {
        SegmentRow(
            segment: TranscriptSegment(
                startTime: 0,
                endTime: 5,
                text: "Hello, this is a sample transcript segment."
            ),
            isActive: false,
            isEditing: false,
            onTap: {},
            onTextChange: { _ in }
        )

        SegmentRow(
            segment: TranscriptSegment(
                startTime: 5,
                endTime: 10,
                text: "This segment is currently playing and highlighted."
            ),
            isActive: true,
            isEditing: false,
            onTap: {},
            onTextChange: { _ in }
        )

        SegmentRow(
            segment: TranscriptSegment(
                startTime: 10,
                endTime: 15,
                text: "This segment is in edit mode."
            ),
            isActive: false,
            isEditing: true,
            onTap: {},
            onTextChange: { _ in }
        )
    }
    .padding()
}
