import WidgetKit
import SwiftUI

// MARK: - Widget Entry

struct SecureVoxEntry: TimelineEntry {
    let date: Date
    let totalRecordings: Int
    let totalDuration: TimeInterval
    let lastRecordingTitle: String?
    let lastRecordingDate: Date?
    let lastRecordingDuration: TimeInterval?
    let isRecording: Bool
    let currentRecordingDuration: TimeInterval?

    static var placeholder: SecureVoxEntry {
        SecureVoxEntry(
            date: Date(),
            totalRecordings: 12,
            totalDuration: 3600,
            lastRecordingTitle: "Meeting Notes",
            lastRecordingDate: Date(),
            lastRecordingDuration: 180,
            isRecording: false,
            currentRecordingDuration: nil
        )
    }

    static var empty: SecureVoxEntry {
        SecureVoxEntry(
            date: Date(),
            totalRecordings: 0,
            totalDuration: 0,
            lastRecordingTitle: nil,
            lastRecordingDate: nil,
            lastRecordingDuration: nil,
            isRecording: false,
            currentRecordingDuration: nil
        )
    }
}

// MARK: - Timeline Provider

struct SecureVoxTimelineProvider: TimelineProvider {
    typealias Entry = SecureVoxEntry

    func placeholder(in context: Context) -> SecureVoxEntry {
        .placeholder
    }

    func getSnapshot(in context: Context, completion: @escaping (SecureVoxEntry) -> Void) {
        let entry = createEntry()
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SecureVoxEntry>) -> Void) {
        let entry = createEntry()

        // Update every 15 minutes
        let nextUpdate = Calendar.current.date(byAdding: .minute, value: 15, to: Date()) ?? Date()
        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
        completion(timeline)
    }

    private func createEntry() -> SecureVoxEntry {
        let data = AppGroupManager.readWidgetData()

        return SecureVoxEntry(
            date: Date(),
            totalRecordings: data.totalRecordings,
            totalDuration: data.totalDuration,
            lastRecordingTitle: data.lastRecordingTitle,
            lastRecordingDate: data.lastRecordingDate,
            lastRecordingDuration: data.lastRecordingDuration,
            isRecording: data.isRecording,
            currentRecordingDuration: data.currentRecordingDuration
        )
    }
}

// MARK: - Small Widget View

struct SmallWidgetView: View {
    let entry: SecureVoxEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: entry.isRecording ? "mic.fill" : "waveform")
                    .font(.title2)
                    .foregroundStyle(entry.isRecording ? .red : .accentColor)

                Spacer()
            }

            Spacer()

            if entry.isRecording {
                Text("Recording...")
                    .font(.headline)
                    .foregroundStyle(.red)

                if let duration = entry.currentRecordingDuration {
                    Text(formatDuration(duration))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else if entry.totalRecordings > 0 {
                Text("\(entry.totalRecordings)")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("recordings")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("Tap to record")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Medium Widget View

struct MediumWidgetView: View {
    let entry: SecureVoxEntry

    var body: some View {
        HStack(spacing: 16) {
            // Left side - stats
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: entry.isRecording ? "mic.fill" : "waveform")
                        .font(.title2)
                        .foregroundStyle(entry.isRecording ? .red : .accentColor)

                    Text("SecureVox")
                        .font(.headline)
                }

                Spacer()

                if entry.isRecording {
                    Text("Recording...")
                        .font(.headline)
                        .foregroundStyle(.red)
                } else {
                    HStack(spacing: 16) {
                        VStack(alignment: .leading) {
                            Text("\(entry.totalRecordings)")
                                .font(.title2)
                                .fontWeight(.bold)
                            Text("recordings")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        VStack(alignment: .leading) {
                            Text(formatTotalDuration(entry.totalDuration))
                                .font(.title2)
                                .fontWeight(.bold)
                            Text("total time")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Divider()

            // Right side - recent recording
            VStack(alignment: .leading, spacing: 4) {
                Text("Recent")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let title = entry.lastRecordingTitle {
                    Text(title)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(2)

                    if let date = entry.lastRecordingDate {
                        Text(date, style: .relative)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                } else {
                    Text("No recordings yet")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private func formatTotalDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60

        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else {
            return "\(minutes)m"
        }
    }
}

// MARK: - Large Widget View

struct LargeWidgetView: View {
    let entry: SecureVoxEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                Image(systemName: entry.isRecording ? "mic.fill" : "waveform")
                    .font(.title2)
                    .foregroundStyle(entry.isRecording ? .red : .accentColor)

                Text("SecureVox")
                    .font(.headline)

                Spacer()

                if entry.isRecording {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(.red)
                            .frame(width: 8, height: 8)
                        Text("Recording")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }

            Divider()

            // Stats grid
            HStack(spacing: 20) {
                StatCard(
                    value: "\(entry.totalRecordings)",
                    label: "Recordings",
                    icon: "doc.text"
                )

                StatCard(
                    value: formatTotalDuration(entry.totalDuration),
                    label: "Total Time",
                    icon: "clock"
                )
            }

            Divider()

            // Recent recording section
            VStack(alignment: .leading, spacing: 8) {
                Text("Most Recent")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let title = entry.lastRecordingTitle {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(title)
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .lineLimit(1)

                            HStack(spacing: 12) {
                                if let date = entry.lastRecordingDate {
                                    Text(date, style: .relative)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                if let duration = entry.lastRecordingDuration {
                                    Label(formatDuration(duration), systemImage: "clock")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .padding(12)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    Text("No recordings yet. Tap to start!")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }

            Spacer()

            // Quick action hint
            HStack {
                Image(systemName: "mic.fill")
                    .font(.caption)
                Text("Tap to start recording")
                    .font(.caption)
            }
            .foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity)
        }
        .padding()
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private func formatTotalDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60

        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else {
            return "\(minutes)m"
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Stat Card

struct StatCard: View {
    let value: String
    let label: String
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(value)
                .font(.title3)
                .fontWeight(.bold)

            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Widget Configuration

struct SecureVoxWidget: Widget {
    let kind: String = "SecureVoxWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: SecureVoxTimelineProvider()) { entry in
            SecureVoxWidgetEntryView(entry: entry)
                .widgetURL(URL(string: "securevox://record"))
        }
        .configurationDisplayName("SecureVox")
        .description("Tap to start recording instantly")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Widget Entry View

struct SecureVoxWidgetEntryView: View {
    @Environment(\.widgetFamily) var family
    let entry: SecureVoxEntry

    var body: some View {
        switch family {
        case .systemSmall:
            SmallWidgetView(entry: entry)
        case .systemMedium:
            MediumWidgetView(entry: entry)
        case .systemLarge:
            LargeWidgetView(entry: entry)
        default:
            SmallWidgetView(entry: entry)
        }
    }
}

// MARK: - Preview

#Preview(as: .systemSmall) {
    SecureVoxWidget()
} timeline: {
    SecureVoxEntry.placeholder
    SecureVoxEntry.empty
}

#Preview(as: .systemMedium) {
    SecureVoxWidget()
} timeline: {
    SecureVoxEntry.placeholder
}

#Preview(as: .systemLarge) {
    SecureVoxWidget()
} timeline: {
    SecureVoxEntry.placeholder
}
