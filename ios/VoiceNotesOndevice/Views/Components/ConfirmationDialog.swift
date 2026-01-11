import SwiftUI

/// Reusable confirmation dialog configuration
struct ConfirmationDialogConfig {
    let title: String
    let message: String?
    let primaryAction: ConfirmationAction
    let secondaryAction: ConfirmationAction?
    let destructiveAction: ConfirmationAction?

    struct ConfirmationAction {
        let title: String
        let action: () -> Void
    }

    init(
        title: String,
        message: String? = nil,
        primaryAction: ConfirmationAction,
        secondaryAction: ConfirmationAction? = nil,
        destructiveAction: ConfirmationAction? = nil
    ) {
        self.title = title
        self.message = message
        self.primaryAction = primaryAction
        self.secondaryAction = secondaryAction
        self.destructiveAction = destructiveAction
    }
}

// MARK: - Delete Confirmation Presets

extension ConfirmationDialogConfig {

    static func deleteRecording(onDelete: @escaping () -> Void) -> ConfirmationDialogConfig {
        ConfirmationDialogConfig(
            title: "Delete Recording?",
            message: "This will permanently delete the recording and its transcript.",
            primaryAction: ConfirmationAction(title: "Cancel", action: {}),
            destructiveAction: ConfirmationAction(title: "Delete", action: onDelete)
        )
    }

    static func deleteAudioOnly(onDelete: @escaping () -> Void) -> ConfirmationDialogConfig {
        ConfirmationDialogConfig(
            title: "Delete Audio File?",
            message: "The transcript will be preserved. This cannot be undone.",
            primaryAction: ConfirmationAction(title: "Cancel", action: {}),
            destructiveAction: ConfirmationAction(title: "Delete Audio", action: onDelete)
        )
    }

    static func cancelRecording(onDiscard: @escaping () -> Void, onKeep: @escaping () -> Void) -> ConfirmationDialogConfig {
        ConfirmationDialogConfig(
            title: "Cancel Recording?",
            message: "Your recording will be lost.",
            primaryAction: ConfirmationAction(title: "Keep Recording", action: onKeep),
            destructiveAction: ConfirmationAction(title: "Discard", action: onDiscard)
        )
    }

    static func deleteMultiple(count: Int, onDelete: @escaping () -> Void) -> ConfirmationDialogConfig {
        ConfirmationDialogConfig(
            title: "Delete \(count) Recording\(count == 1 ? "" : "s")?",
            message: "This will permanently delete the selected recordings and their transcripts.",
            primaryAction: ConfirmationAction(title: "Cancel", action: {}),
            destructiveAction: ConfirmationAction(title: "Delete All", action: onDelete)
        )
    }
}
