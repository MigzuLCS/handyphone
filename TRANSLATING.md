# Fixing or adding a translation

Migzu's HandyPhone is in English and Portuguese, written by hand. **Spanish, French, German and Russian were translated with AI and
have not been reviewed by native speakers yet**, so they may sound odd or be wrong in places. Corrections are very welcome!

All text lives in one folder: [`src/main/resources/assets/handyphone/lang/`](src/main/resources/assets/handyphone/lang/).
`en_us.json` is the reference: every other file has the same keys.

## Quick fix (no GitHub skills needed)

Open an [issue](../../issues/new) with the title `Translation (<language>)` and write:

- the key or the text as it appears in the game,
- what is wrong and what it should say.

A screenshot helps a lot. Mention where you saw the text (which app, which phone style).

## Fix it yourself (pull request)

1. Open the file for your language, for example
   [`fr_fr.json`](src/main/resources/assets/handyphone/lang/fr_fr.json), and click the pencil icon to edit it on GitHub.
2. Change **only the text on the right**. Never rename or delete a key (the part on the left).
3. Propose the change. GitHub opens a pull request for you.

### Rules

- **Placeholders:** keep every `%s` and `%1$s`, the same number as in English. You can move them around in the sentence.
- **Keep it short.** The phone screen is small. App names and map buttons are the tightest; if the English is one word, aim for one word.
- **Plurals:** keys ending in `.one`, `.few`, `.many` and `.other` are forms of the same text.
  - English, Portuguese, Spanish, German: `one` (1) and `other`.
  - French: `one` (0 and 1) and `other`.
  - Russian (and Ukrainian, Belarusian, Polish): `one`, `few`, `many` and `other`.
- **Dates and time** (`handyphone.format.*`) are [Java date patterns](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/format/DateTimeFormatter.html#patterns)
  (`d` day, `MMM` short month, `EEEE` weekday, `HH:mm` 24-hour time). Month and day names come from Java itself, in your language.
  Don't add a `.` after `MMM` or `EEE`: many languages already include it.
- **Don't translate:** the mod name *HandyPhone*, key names (`key.*` is fine to translate, it's the text that matters), or anything in `%s`.

## A new language

Copy `en_us.json` to your language code (`it_it.json`, `pl_pl.json`, `ja_jp.json`...) and translate it. Missing keys fall back to
English, so a partial translation still works. Open a pull request or attach the file to an issue.

Note: Chinese, Japanese and Korean show up only if the player's system has fonts for them (the phone's built-in font is Latin, Greek and
Cyrillic only).

## Testing your translation in game

You don't need to build the mod. Make a resource pack:

```
MyTranslation/
├── pack.mcmeta
└── assets/handyphone/lang/fr_fr.json
```

`pack.mcmeta`:

```json
{ "pack": { "description": "HandyPhone translation", "min_format": 1, "max_format": 999 } }
```

Put the folder in `.minecraft/resourcepacks`, enable it in the game, choose your language and press **U**. Your file wins over the one
in the mod. Changing the file? Press **F3 + T** to reload.

Regional variants (like `es_mx` or `fr_ca`) get a copy of the main language automatically. If yours needs different words, send a
file just for it.

## License of contributions

By sending a translation (issue, pull request or file), you agree that it can be included and distributed in Migzu's HandyPhone under
its [license](LICENSE), and you'll be credited in the changelog of the version that includes it (tell us the name you want).
