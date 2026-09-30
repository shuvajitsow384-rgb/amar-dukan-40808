package com.example.utils

import java.util.Locale
import java.util.regex.Pattern

/**
 * Utility to provide seamless, high-accuracy translation and formatting for store receipt bills.
 * Supports store names, addresses, product names (especially grocery, stationery, FMCG),
 * quantities, units, payment modes, staff titles, and footer slogans for Bengali and English.
 */
object BengaliReceiptTranslator {

    // Common store name translations
    fun translateStoreName(name: String): String {
        if (name.isBlank()) return "কালী মাতা ভ্যারাইটি স্টোর"
        if (containsBengali(name)) return name

        val lower = name.trim().lowercase(Locale.ROOT)
        if (lower.contains("kali mata") || lower.contains("kalimata")) {
            return "কালী মাতা ভ্যারাইটি স্টোর"
        }

        var result = name
        val replacements = listOf(
            "variety store" to "ভ্যারাইটি স্টোর",
            "general store" to "জেনারেল স্টোর",
            "grocery store" to "মুদি ভাণ্ডার",
            "grocery shop" to "মুদি খানা",
            "departmental store" to "ডিপার্টমেন্টাল স্টোর",
            "super market" to "সুপার মার্কেট",
            "supermarket" to "সুপার মার্কেট",
            "enterprise" to "এন্টারপ্রাইজ",
            "enterprises" to "এন্টারপ্রাইজেস",
            "medical store" to "মেডিক্যাল স্টোর",
            "pharmacy" to "ফার্মেসি",
            "stationery" to "স্টেশনারি",
            "stationery shop" to "স্টেশনারি দোকান",
            "bhandar" to "ভাণ্ডার",
            "store" to "স্টোর",
            "shop" to "দোকান"
        )
        for ((en, bn) in replacements) {
            val pattern = Pattern.compile("(?i)\\b" + Pattern.quote(en) + "\\b")
            result = pattern.matcher(result).replaceAll(bn)
        }
        return result
    }

    // Common address translations
    fun translateAddress(address: String): String {
        if (address.isBlank()) return "কুরমিঠা, বীরভূম জেলা, পশ্চিমবঙ্গ"
        if (containsBengali(address)) return address

        val lower = address.trim().lowercase(Locale.ROOT)
        if (lower.contains("kurmitha") && lower.contains("birbhum")) {
            return "কুরমিঠা, বীরভূম জেলা, পশ্চিমবঙ্গ"
        }

        var result = address
        val replacements = listOf(
            "kurmitha" to "কুরমিঠা",
            "birbhum district" to "বীরভূম জেলা",
            "birbhum dist" to "বীরভূম জেলা",
            "birbhum" to "বীরভূম",
            "west bengal" to "পশ্চিমবঙ্গ",
            "district" to "জেলা",
            "dist." to "জেলা",
            "dist" to "জেলা",
            "post" to "পোস্ট",
            "p.o." to "পোস্ট",
            "p.s." to "থানা",
            "police station" to "থানা",
            "pin" to "পিন",
            "kolkata" to "কলকাতা",
            "calcutta" to "কলকাতা",
            "bardhaman" to "বর্ধমান",
            "burdwan" to "বর্ধমান",
            "bankura" to "বাঁকুড়া",
            "purulia" to "পুরুলিয়া",
            "murshidabad" to "মুর্শিদাবাদ",
            "nadia" to "নদীয়া",
            "hooghly" to "হুগলি",
            "howrah" to "হাওড়া",
            "malda" to "মালদা",
            "road" to "রোড",
            "market" to "মার্কেট",
            "bazar" to "বাজার",
            "hat" to "হাট",
            "station" to "স্টেশন",
            "near" to "নিকট"
        )
        for ((en, bn) in replacements) {
            val pattern = Pattern.compile("(?i)\\b" + Pattern.quote(en) + "\\b")
            result = pattern.matcher(result).replaceAll(bn)
        }
        return result
    }

    // Payment mode translations
    fun translatePaymentMode(mode: String, isBn: Boolean): String {
        val upper = mode.trim().uppercase(Locale.ROOT)
        if (!isBn) {
            return when {
                upper.contains("CREDIT") || upper.contains("KHATA") || upper.contains("BAKI") || upper.contains("DUE") -> "Due (credit)"
                upper == "CASH" -> "Cash"
                upper == "UPI" || upper == "GPAY" || upper == "PHONEPE" || upper == "PAYTM" || upper.contains("ONLINE") -> "UPI"
                upper.contains("CARD") || upper.contains("DEBIT") -> "Card"
                else -> mode
            }
        }
        return when {
            upper == "CASH" -> "নগদ (ক্যাশ)"
            upper == "UPI" || upper == "GPAY" || upper == "PHONEPE" || upper == "PAYTM" || upper.contains("ONLINE") -> "অনলাইন (UPI)"
            upper.contains("CREDIT") || upper.contains("KHATA") || upper.contains("BAKI") || upper.contains("DUE") -> "বাকী (ক্রেডিট)"
            upper.contains("CARD") || upper.contains("DEBIT") -> "কার্ড (Card)"
            else -> mode
        }
    }

    // Staff role / name translations - drops role tags e.g. (Owner/Admin)
    fun translateStaffName(staffName: String?, isBn: Boolean): String {
        if (staffName.isNullOrBlank()) return ""
        val cleaned = staffName
            .replace(Regex("(?i)\\s*\\(\\s*(?:store\\s+)?owner\\s*(?:[/|&,]\\s*)?(?:admin)?\\s*\\)"), "")
            .replace(Regex("(?i)\\s*\\(\\s*admin\\s*\\)"), "")
            .replace(Regex("(?i)\\s*\\(\\s*owner\\s*\\)"), "")
            .replace(Regex("\\s*\\(\\s*(?:দোকান\\s+)?মালিক\\s*(?:[/|&,]\\s*)?(?:অ্যাডমিন)?\\s*\\)"), "")
            .replace(Regex("\\s*\\(\\s*অ্যাডমিন\\s*\\)"), "")
            .replace(Regex("\\s*\\(\\s*মালিক\\s*\\)"), "")
            .trim()

        if (cleaned.isBlank()) return if (isBn) "দোকান মালিক" else "Store Owner"
        if (!isBn) return cleaned

        val lower = cleaned.lowercase(Locale.ROOT)
        return when {
            lower == "store owner" || lower == "owner" -> "দোকান মালিক"
            lower == "admin" -> "অ্যাডমিন"
            lower == "staff" -> "দোকান কর্মী"
            lower == "cashier" -> "ক্যাশিয়ার"
            lower == "manager" -> "ম্যানেজার"
            else -> cleaned
        }
    }

    // Customer name translation
    fun translateCustomerName(customerName: String?, isBn: Boolean): String {
        val name = customerName?.trim()
        if (name.isNullOrBlank() || name.equals("counter cash", ignoreCase = true) || name.equals("walk-in customer", ignoreCase = true) || name.equals("walk-in", ignoreCase = true)) {
            return if (isBn) "সাধারণ গ্রাহক" else "Counter Cash"
        }
        return name
    }

    // Comprehensive grocery/retail product translations dictionary
    private val ITEM_DICTIONARY = mapOf(
        "sugar" to "চিনি",
        "minikit rice" to "মিনিকিট চাল",
        "miniket rice" to "মিনিকিট চাল",
        "minikit" to "মিনিকিট চাল",
        "miniket" to "মিনিকিট চাল",
        "rice" to "চাল",
        "basmati rice" to "বাসমতী চাল",
        "gobindobhog rice" to "গোবিন্দভোগ চাল",
        "horlicks" to "হরলিক্স",
        "egg" to "ডিম",
        "eggs" to "ডিম",
        "salt" to "লবণ",
        "tata salt" to "টাটা লবণ",
        "mustard oil" to "সরিষার তেল",
        "refined oil" to "সাদা তেল",
        "soyabean oil" to "সয়াবিন তেল",
        "white oil" to "সাদা তেল",
        "oil" to "তেল",
        "cooking oil" to "রান্নার তেল",
        "milk" to "দুধ",
        "tea" to "চা পাতা",
        "tea leaf" to "চা পাতা",
        "coffee" to "কফি",
        "biscuit" to "বিস্কুট",
        "biscuits" to "বিস্কুট",
        "cookies" to "বিস্কুট",
        "atta" to "আটা",
        "flour" to "আটা",
        "wheat flour" to "গমের আটা",
        "maida" to "ময়দা",
        "suji" to "সুজি",
        "semolina" to "সুজি",
        "dal" to "ডাল",
        "pulses" to "ডাল",
        "masoor dal" to "মুসুর ডাল",
        "musur dal" to "মুসুর ডাল",
        "moong dal" to "মুগ ডাল",
        "chana dal" to "ছোলার ডাল",
        "urad dal" to "বিউলির ডাল",
        "biuli dal" to "বিউলির ডাল",
        "matar dal" to "মটর ডাল",
        "potato" to "আলু",
        "potatoes" to "আলু",
        "aloo" to "আলু",
        "onion" to "পেঁয়াজ",
        "onions" to "পেঁয়াজ",
        "pyaz" to "পেঁয়াজ",
        "garlic" to "রসুন",
        "lahsun" to "রসুন",
        "ginger" to "আদা",
        "adrak" to "আদা",
        "chilli" to "লঙ্কা",
        "mirch" to "লঙ্কা",
        "green chilli" to "কাঁচা লঙ্কা",
        "red chilli" to "শুকনো লঙ্কা",
        "dry chilli" to "শুকনো লঙ্কা",
        "turmeric" to "হলুদ",
        "haldi" to "হলুদ",
        "turmeric powder" to "হলুদ গুঁড়ো",
        "chilli powder" to "লঙ্কা গুঁড়ো",
        "cumin" to "জিরে",
        "jeera" to "জিরে",
        "cumin powder" to "জিরে গুঁড়ো",
        "coriander" to "ধনে",
        "dhaniya" to "ধনে",
        "coriander powder" to "ধনে গুঁড়ো",
        "garam masala" to "গরম মশলা",
        "soap" to "সাবান",
        "bath soap" to "গা ধোয়ার সাবান",
        "detergent" to "ডিটারজেন্ট",
        "washing powder" to "ডিটারজেন্ট পাউডার",
        "detergent powder" to "ডিটারজেন্ট পাউডার",
        "washing soap" to "কাপড় কাঁচা সাবান",
        "surf" to "সার্ফ পাউডার",
        "shampoo" to "শ্যাম্পু",
        "toothpaste" to "টুথপেস্ট",
        "toothbrush" to "টুথব্রাশ",
        "hair oil" to "মাথার তেল",
        "coconut oil" to "নারকেল তেল",
        "matchbox" to "দেশলাই",
        "matches" to "দেশলাই",
        "besan" to "বেসন",
        "gram flour" to "বেসন",
        "chira" to "চিঁড়ে",
        "poha" to "চিঁড়ে",
        "flattened rice" to "চিঁড়ে",
        "muri" to "মুড়ি",
        "puffed rice" to "মুড়ি",
        "noodles" to "ম্যাগি / চাউমিন",
        "maggi" to "ম্যাগি",
        "pasta" to "পাস্তা",
        "ghee" to "ঘি",
        "butter" to "মাখন",
        "curd" to "দই",
        "dahi" to "দই",
        "paneer" to "পনির",
        "bread" to "পাউরুটি",
        "toast" to "টোস্ট",
        "chanachur" to "চানাচুর",
        "mixture" to "চানাচুর",
        "chips" to "চিপস",
        "chocolate" to "চকোলেট",
        "chocolates" to "চকোলেট",
        "pen" to "কলম",
        "ball pen" to "বল পেন",
        "khata" to "খাতা",
        "notebook" to "খাতা",
        "copy" to "খাতা",
        "pencil" to "পেন্সিল",
        "eraser" to "ইরেজার / রবার",
        "sharpener" to "শার্পনার",
        "scale" to "স্কেল",
        "agarbatti" to "ধূপকাঠি",
        "incense" to "ধূপকাঠি",
        "incense sticks" to "ধূপকাঠি",
        "candle" to "মোমবাতি",
        "candles" to "মোমবাতি",
        "dhoop" to "ধূপ",
        "camphor" to "কর্পূর",
        "water bottle" to "পানির বোতল",
        "mineral water" to "মিনারেল ওয়াটার",
        "soft drink" to "কোল্ড ড্রিঙ্কস",
        "cold drink" to "কোল্ড ড্রিঙ্কস"
    )

    /**
     * Identifies if a gift offer name is generic (e.g. "free gift", "Free Gift", "Free Gift on ₹500+ Bill", "উপহার", "ফ্রি উপহার", etc.)
     */
    fun isGenericGiftOfferName(offerName: String?): Boolean {
        if (offerName.isNullOrBlank()) return true
        val normalized = offerName.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\u0980-\u09ff]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return true
        val genericWords = setOf(
            "free", "gift", "offer", "freegift", "gifts", "offers",
            "ফ্রি", "উপহার", "অফার", "ফ্রিউপহার", "বিনামূল্যে",
            "purchase", "purchases", "bill", "on", "with", "কেনাকাটা", "কেনাকাটায়"
        )
        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        val nonNumericTokens = tokens.filterNot { it.all { ch -> ch.isDigit() || ch in '০'..'৯' } }
        return nonNumericTokens.isEmpty() || nonNumericTokens.all { it in genericWords }
    }

    /**
     * Extracts a clean, non-redundant campaign name for free gifts.
     * Returns empty string if the offer name is generic or redundant.
     */
    fun extractCleanGiftCampaignName(offerName: String?): String {
        if (isGenericGiftOfferName(offerName)) return ""
        val trimmed = offerName?.trim() ?: return ""
        val cleaned = trimmed
            .replace(Regex("(?i)\\b(free\\s*gift|free|gift|with purchase|on purchase|purchases|bill|offer)\\b"), "")
            .replace(Regex("(?i)\\b(ফ্রি\\s*উপহার|ফ্রি|উপহার|কেনাকাটায়|অফার)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length >= 2) cleaned else trimmed
    }

    /**
     * Cleans emojis and repetitive or messy tags from receipt product names.
     */
    fun cleanReceiptProductName(name: String): String {
        if (name.isBlank()) return ""
        var cleaned = name
            .replace("🏷️", "")
            .replace("🏷", "")
            .replace("🎉", "")
            .replace("🎁", "")
            .replace("🙏", "")
            .replace("⚠️", "")
            .replace("📅", "")
            .replace("❌", "")
            .replace("👤", "")
            .replace("ℹ️", "")

        // Clean redundant [FREE GIFT] or (Free Gift: ...) variations
        cleaned = cleaned.replace(Regex("(?i)\\[(?:FREE GIFT|ফ্রি উপহার)\\]\\s*\\((?:FREE GIFT|ফ্রি উপহার)(?::\\s*([^)]+))?\\)")) { m ->
            val off = extractCleanGiftCampaignName(m.groupValues[1])
            val isBnContext = containsBengali(m.value)
            if (off.isBlank()) {
                if (isBnContext) "(ফ্রি উপহার)" else "(Free Gift)"
            } else {
                if (isBnContext) "(ফ্রি উপহার: $off)" else "(Free Gift: $off)"
            }
        }

        cleaned = cleaned.replace(Regex("(?i)\\((?:FREE GIFT|ফ্রি উপহার):\\s*([^)]+)\\)")) { m ->
            val off = extractCleanGiftCampaignName(m.groupValues[1])
            val isBnContext = containsBengali(m.value)
            if (off.isBlank()) {
                if (isBnContext) "(ফ্রি উপহার)" else "(Free Gift)"
            } else {
                if (isBnContext) "(ফ্রি উপহার: $off)" else "(Free Gift: $off)"
            }
        }

        cleaned = cleaned.replace(Regex("(?i)\\[FREE GIFT\\]"), "(Free Gift)")
        cleaned = cleaned.replace(Regex("\\[ফ্রি উপহার\\]"), "(ফ্রি উপহার)")

        // Deduplicate multiple adjacent (Free Gift) tags
        cleaned = cleaned.replace(Regex("(?i)\\((?:Free Gift|ফ্রি উপহার)\\)\\s*\\((?:Free Gift|ফ্রি উপহার)\\)"), "(Free Gift)")

        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Translates product name to Bengali.
     * Handles composite names like "horlicks 500g" -> "হরলিক্স ৫০০ গ্রাম",
     * "minikit rice" -> "মিনিকিট চাল", etc.
     */
    fun translateItem(name: String, nameBn: String = ""): String {
        if (nameBn.isNotBlank() && containsBengali(nameBn)) return cleanReceiptProductName(nameBn)
        if (name.isBlank()) return "পণ্য"

        val cleaned = cleanReceiptProductName(name)
        if (containsBengali(cleaned)) return cleaned

        // Check if there is a gift suffix e.g. "(Free Gift)" or "(Free Gift: ...)"
        var baseText = cleaned
        var giftSuffixBn = ""
        val giftRegex = Regex("(?i)\\((Free Gift|ফ্রি উপহার)(?::\\s*([^)]+))?\\)")
        val giftMatch = giftRegex.find(cleaned)
        if (giftMatch != null) {
            val offer = extractCleanGiftCampaignName(giftMatch.groupValues[2])
            giftSuffixBn = if (offer.isNotBlank()) {
                " (ফ্রি উপহার: $offer)"
            } else {
                " (ফ্রি উপহার)"
            }
            baseText = cleaned.removeRange(giftMatch.range).trim()
        }

        val trimmed = baseText.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Direct dictionary match
        val dictMatch = ITEM_DICTIONARY[lower]
        if (dictMatch != null) {
            return "$dictMatch$giftSuffixBn".trim()
        }

        // 2. Unit and number extraction (e.g. "horlicks 500g", "sugar 1kg")
        var working = trimmed
        var unitSuffix = ""

        val unitPatterns = listOf(
            Regex("(?i)\\b(\\d+)\\s*(kg|kilo|kilogram)\\b") to { match: MatchResult -> "${match.groupValues[1]} কেজি" },
            Regex("(?i)\\b(\\d+)\\s*(g|gm|gram|grams)\\b") to { match: MatchResult -> "${match.groupValues[1]} গ্রাম" },
            Regex("(?i)\\b(\\d+)\\s*(l|ltr|liter|litre|litres)\\b") to { match: MatchResult -> "${match.groupValues[1]} লিটার" },
            Regex("(?i)\\b(\\d+)\\s*(ml|milli)\\b") to { match: MatchResult -> "${match.groupValues[1]} মিলি" },
            Regex("(?i)\\b(\\d+)\\s*(pc|pcs|piece|pieces)\\b") to { match: MatchResult -> "${match.groupValues[1]} পিস" },
            Regex("(?i)\\b(\\d+)\\s*(pkt|packet|packets)\\b") to { match: MatchResult -> "${match.groupValues[1]} প্যাকেট" },
            Regex("(?i)\\b(\\d+)\\s*(box|boxes)\\b") to { match: MatchResult -> "${match.groupValues[1]} বাক্স" }
        )

        for ((regex, formatter) in unitPatterns) {
            val match = regex.find(working)
            if (match != null) {
                unitSuffix = formatter(match)
                working = working.removeRange(match.range).trim()
                break
            }
        }

        val baseLower = working.lowercase(Locale.ROOT).trim()
        val translatedBase = ITEM_DICTIONARY[baseLower]
            ?: findBestMatchInDictionary(baseLower)
            ?: working

        val combined = if (unitSuffix.isNotBlank()) {
            "$translatedBase $unitSuffix".trim()
        } else {
            translatedBase
        }

        return "$combined$giftSuffixBn".trim()
    }

    private fun findBestMatchInDictionary(query: String): String? {
        if (query.length < 3) return null
        for ((en, bn) in ITEM_DICTIONARY) {
            if (query.contains(en)) {
                return bn
            }
        }
        return null
    }

    fun translateUnit(unit: String, isBn: Boolean): String {
        val clean = unit.trim().lowercase(Locale.ROOT)
        if (!isBn) {
            return when {
                clean in listOf("l", "ltr", "ltrs", "liter", "liters", "litre", "litres", "লিটার") -> "Liters"
                clean in listOf("kg", "kgs", "kilogram", "kilograms", "kilo", "kilos", "কেজি") -> "kg"
                clean in listOf("g", "gm", "gms", "gram", "grams", "গ্রাম") -> "gram"
                clean in listOf("ml", "mls", "milliliter", "milliliters", "millilitre", "millilitres", "মিলি") -> "ml"
                clean in listOf("pcs", "piece", "pieces", "pc", "পিস") -> "piece"
                clean in listOf("box", "boxes", "বাক্স", "বক্স") -> "box"
                clean in listOf("pkt", "packet", "packets", "প্যাকেট") -> "packet"
                clean in listOf("doz", "dozen", "dozens", "ডজন") -> "dozen"
                clean in listOf("bottle", "bottles", "বোতল") -> "bottle"
                clean in listOf("bag", "bags", "ব্যাগ", "বস্তা") -> "bag"
                clean in listOf("meter", "metre", "meters", "metres", "m", "মিটার") -> "meter"
                clean in listOf("bundle", "bundles", "বান্ডিল") -> "bundle"
                clean in listOf("strip", "strips", "স্ট্রিপ") -> "strip"
                clean in listOf("can", "cans", "ক্যান") -> "can"
                else -> unit
            }
        }
        return when {
            clean in listOf("kg", "kgs", "kilogram", "kilograms", "kilo", "kilos") || clean.contains("কেজি") -> "কেজি"
            clean in listOf("g", "gm", "gms", "gram", "grams") || clean.contains("গ্রাম") -> "গ্রাম"
            clean in listOf("pcs", "piece", "pieces", "pc") || clean.contains("পিস") -> "পিস"
            clean in listOf("l", "ltr", "ltrs", "liter", "liters", "litre", "litres") || clean.contains("লিটার") -> "লিটার"
            clean in listOf("ml", "mls", "milliliter", "milliliters", "millilitre", "millilitres") || clean.contains("মিলি") -> "মিলি"
            clean in listOf("pkt", "packet", "packets") || clean.contains("প্যাকেট") -> "প্যাকেট"
            clean in listOf("box", "boxes") || clean.contains("বাক্স") || clean.contains("বক্স") -> "বাক্স"
            clean in listOf("doz", "dozen", "dozens") || clean.contains("ডজন") -> "ডজন"
            clean in listOf("bottle", "bottles") || clean.contains("বোতল") -> "বোতল"
            clean in listOf("meter", "metre", "meters", "metres", "m") || clean.contains("মিটার") -> "মিটার"
            clean in listOf("bundle", "bundles") || clean.contains("বান্ডিল") -> "বান্ডিল"
            clean in listOf("strip", "strips") || clean.contains("স্ট্রিপ") -> "স্ট্রিপ"
            clean in listOf("tablet", "tablets") || clean.contains("ট্যাবলেট") -> "ট্যাবলেট"
            clean in listOf("capsule", "capsules") || clean.contains("ক্যাপসুল") -> "ক্যাপসুল"
            clean in listOf("can", "cans") || clean.contains("ক্যান") -> "ক্যান"
            clean in listOf("tin", "tins") || clean.contains("টিন") -> "টিন"
            clean in listOf("bag", "bags") || clean.contains("ব্যাগ") || clean.contains("বস্তা") -> "ব্যাগ"
            clean in listOf("roll", "rolls") || clean.contains("রোল") -> "রোল"
            clean in listOf("jar", "jars") || clean.contains("জার") -> "জার"
            clean in listOf("set", "sets") || clean.contains("সেট") -> "সেট"
            clean in listOf("pair", "pairs") || clean.contains("জোড়া") -> "জোড়া"
            clean in listOf("slice", "slices") || clean.contains("স্লাইস") -> "স্লাইস"
            clean in listOf("sheet", "sheets") || clean.contains("শীট") -> "শীট"
            clean in listOf("leaf", "leaves") || clean.contains("পাতা") -> "পাতা"
            else -> unit
        }
    }

    fun formatQtyWithUnit(qty: Double, unit: String, isBn: Boolean): String {
        val u = translateUnit(unit, isBn)
        val numStr = com.example.data.local.entities.Product.formatQuantity(qty)
        return "$numStr $u"
    }

    fun getFooterGreeting(isBn: Boolean, customNote: String?): String {
        val cleanedNote = customNote?.replace("🙏", "")?.replace("🎁", "")?.trim()
        if (!cleanedNote.isNullOrBlank() && !isDefaultEnglishFooter(cleanedNote)) {
            return cleanedNote
        }
        return if (isBn) {
            "আমাদের সাথে কেনাকাটা করার জন্য ধন্যবাদ! আবার আসবেন"
        } else {
            "Thank you for shopping with us! Please visit again"
        }
    }

    private fun isDefaultEnglishFooter(note: String): Boolean {
        val lower = note.trim().lowercase(Locale.ROOT)
        return lower.contains("thank you for shopping") || lower.contains("visit again")
    }

    fun containsBengali(str: String): Boolean {
        for (char in str) {
            if (char.code in 0x0980..0x09FF) return true
        }
        return false
    }

    fun formatDiscountItemLine(
        mrp: Double,
        unitPrice: Double,
        quantity: Double,
        isBn: Boolean
    ): String {
        val totalSavings = ((mrp - unitPrice) * quantity).coerceAtLeast(0.0)
        val percent = if (mrp > 0.0) (((mrp - unitPrice) / mrp) * 100.0).coerceAtLeast(0.0) else 0.0
        val roundedPct = kotlin.math.round(percent).toInt()
        return if (isBn) {
            "MRP: ₹%.2f | ছাড়: ₹%.2f (%d%%)".format(mrp, totalSavings, roundedPct)
        } else {
            "MRP: Rs %.2f | Save: Rs %.2f (%d%% off)".format(mrp, totalSavings, roundedPct)
        }
    }
}
