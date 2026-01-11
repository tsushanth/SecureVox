import Foundation

/// Represents a language supported by Whisper
struct WhisperLanguage: Identifiable, Hashable {
    let code: String
    let name: String

    var id: String { code }

    /// Localized name using system locale
    var localizedName: String {
        Locale.current.localizedString(forLanguageCode: code) ?? name
    }
}

// MARK: - Supported Languages

extension WhisperLanguage {

    /// Auto-detect option
    static let autoDetect = WhisperLanguage(code: "auto", name: "Auto-Detect")

    /// All languages supported by Whisper
    static let allLanguages: [WhisperLanguage] = [
        autoDetect,
        WhisperLanguage(code: "en", name: "English"),
        WhisperLanguage(code: "zh", name: "Chinese"),
        WhisperLanguage(code: "de", name: "German"),
        WhisperLanguage(code: "es", name: "Spanish"),
        WhisperLanguage(code: "ru", name: "Russian"),
        WhisperLanguage(code: "ko", name: "Korean"),
        WhisperLanguage(code: "fr", name: "French"),
        WhisperLanguage(code: "ja", name: "Japanese"),
        WhisperLanguage(code: "pt", name: "Portuguese"),
        WhisperLanguage(code: "tr", name: "Turkish"),
        WhisperLanguage(code: "pl", name: "Polish"),
        WhisperLanguage(code: "ca", name: "Catalan"),
        WhisperLanguage(code: "nl", name: "Dutch"),
        WhisperLanguage(code: "ar", name: "Arabic"),
        WhisperLanguage(code: "sv", name: "Swedish"),
        WhisperLanguage(code: "it", name: "Italian"),
        WhisperLanguage(code: "id", name: "Indonesian"),
        WhisperLanguage(code: "hi", name: "Hindi"),
        WhisperLanguage(code: "fi", name: "Finnish"),
        WhisperLanguage(code: "vi", name: "Vietnamese"),
        WhisperLanguage(code: "he", name: "Hebrew"),
        WhisperLanguage(code: "uk", name: "Ukrainian"),
        WhisperLanguage(code: "el", name: "Greek"),
        WhisperLanguage(code: "ms", name: "Malay"),
        WhisperLanguage(code: "cs", name: "Czech"),
        WhisperLanguage(code: "ro", name: "Romanian"),
        WhisperLanguage(code: "da", name: "Danish"),
        WhisperLanguage(code: "hu", name: "Hungarian"),
        WhisperLanguage(code: "ta", name: "Tamil"),
        WhisperLanguage(code: "no", name: "Norwegian"),
        WhisperLanguage(code: "th", name: "Thai"),
        WhisperLanguage(code: "ur", name: "Urdu"),
        WhisperLanguage(code: "hr", name: "Croatian"),
        WhisperLanguage(code: "bg", name: "Bulgarian"),
        WhisperLanguage(code: "lt", name: "Lithuanian"),
        WhisperLanguage(code: "la", name: "Latin"),
        WhisperLanguage(code: "mi", name: "Maori"),
        WhisperLanguage(code: "ml", name: "Malayalam"),
        WhisperLanguage(code: "cy", name: "Welsh"),
        WhisperLanguage(code: "sk", name: "Slovak"),
        WhisperLanguage(code: "te", name: "Telugu"),
        WhisperLanguage(code: "fa", name: "Persian"),
        WhisperLanguage(code: "lv", name: "Latvian"),
        WhisperLanguage(code: "bn", name: "Bengali"),
        WhisperLanguage(code: "sr", name: "Serbian"),
        WhisperLanguage(code: "az", name: "Azerbaijani"),
        WhisperLanguage(code: "sl", name: "Slovenian"),
        WhisperLanguage(code: "kn", name: "Kannada"),
        WhisperLanguage(code: "et", name: "Estonian"),
        WhisperLanguage(code: "mk", name: "Macedonian"),
        WhisperLanguage(code: "br", name: "Breton"),
        WhisperLanguage(code: "eu", name: "Basque"),
        WhisperLanguage(code: "is", name: "Icelandic"),
        WhisperLanguage(code: "hy", name: "Armenian"),
        WhisperLanguage(code: "ne", name: "Nepali"),
        WhisperLanguage(code: "mn", name: "Mongolian"),
        WhisperLanguage(code: "bs", name: "Bosnian"),
        WhisperLanguage(code: "kk", name: "Kazakh"),
        WhisperLanguage(code: "sq", name: "Albanian"),
        WhisperLanguage(code: "sw", name: "Swahili"),
        WhisperLanguage(code: "gl", name: "Galician"),
        WhisperLanguage(code: "mr", name: "Marathi"),
        WhisperLanguage(code: "pa", name: "Punjabi"),
        WhisperLanguage(code: "si", name: "Sinhala"),
        WhisperLanguage(code: "km", name: "Khmer"),
        WhisperLanguage(code: "sn", name: "Shona"),
        WhisperLanguage(code: "yo", name: "Yoruba"),
        WhisperLanguage(code: "so", name: "Somali"),
        WhisperLanguage(code: "af", name: "Afrikaans"),
        WhisperLanguage(code: "oc", name: "Occitan"),
        WhisperLanguage(code: "ka", name: "Georgian"),
        WhisperLanguage(code: "be", name: "Belarusian"),
        WhisperLanguage(code: "tg", name: "Tajik"),
        WhisperLanguage(code: "sd", name: "Sindhi"),
        WhisperLanguage(code: "gu", name: "Gujarati"),
        WhisperLanguage(code: "am", name: "Amharic"),
        WhisperLanguage(code: "yi", name: "Yiddish"),
        WhisperLanguage(code: "lo", name: "Lao"),
        WhisperLanguage(code: "uz", name: "Uzbek"),
        WhisperLanguage(code: "fo", name: "Faroese"),
        WhisperLanguage(code: "ht", name: "Haitian Creole"),
        WhisperLanguage(code: "ps", name: "Pashto"),
        WhisperLanguage(code: "tk", name: "Turkmen"),
        WhisperLanguage(code: "nn", name: "Nynorsk"),
        WhisperLanguage(code: "mt", name: "Maltese"),
        WhisperLanguage(code: "sa", name: "Sanskrit"),
        WhisperLanguage(code: "lb", name: "Luxembourgish"),
        WhisperLanguage(code: "my", name: "Myanmar"),
        WhisperLanguage(code: "bo", name: "Tibetan"),
        WhisperLanguage(code: "tl", name: "Tagalog"),
        WhisperLanguage(code: "mg", name: "Malagasy"),
        WhisperLanguage(code: "as", name: "Assamese"),
        WhisperLanguage(code: "tt", name: "Tatar"),
        WhisperLanguage(code: "haw", name: "Hawaiian"),
        WhisperLanguage(code: "ln", name: "Lingala"),
        WhisperLanguage(code: "ha", name: "Hausa"),
        WhisperLanguage(code: "ba", name: "Bashkir"),
        WhisperLanguage(code: "jw", name: "Javanese"),
        WhisperLanguage(code: "su", name: "Sundanese")
    ]

    /// Find language by code
    static func find(byCode code: String) -> WhisperLanguage? {
        allLanguages.first { $0.code == code }
    }

    /// Common languages (shown at top of picker)
    static let commonLanguages: [WhisperLanguage] = [
        autoDetect,
        WhisperLanguage(code: "en", name: "English"),
        WhisperLanguage(code: "es", name: "Spanish"),
        WhisperLanguage(code: "fr", name: "French"),
        WhisperLanguage(code: "de", name: "German"),
        WhisperLanguage(code: "zh", name: "Chinese"),
        WhisperLanguage(code: "ja", name: "Japanese"),
        WhisperLanguage(code: "ko", name: "Korean"),
        WhisperLanguage(code: "pt", name: "Portuguese"),
        WhisperLanguage(code: "it", name: "Italian"),
        WhisperLanguage(code: "ru", name: "Russian"),
        WhisperLanguage(code: "ar", name: "Arabic"),
        WhisperLanguage(code: "hi", name: "Hindi")
    ]
}
