package pl.lukaszpeciak.towarownik.product

internal const val DEFAULT_OBI_STORE_NUMBER = "075"

internal data class ObiStoreMetadata(
    val storeNumber: String,
    val city: String,
    val address: String,
)

// Verified against the official OBI Poland customer-relations market directory
// https://www.obi.pl/info/dzial-relacji on 2026-10-07.
// The relacje_plNNN mailbox suffix is the authoritative OBI market number.
// Runtime code never discovers or expands this allowlist.
internal val OBI_STORES: List<ObiStoreMetadata> = listOf(
    ObiStoreMetadata("022", "Bydgoszcz", "ul. Fabryczna 1"),
    ObiStoreMetadata("006", "Bytom", "ul. Chorzowska 86"),
    ObiStoreMetadata("066", "Bytom", "ul. Strzelców Bytomskich 96"),
    ObiStoreMetadata("064", "Czeladź", "ul. Będzińska 80"),
    ObiStoreMetadata("067", "Częstochowa", "ul. Kisielewskiego 8/16"),
    ObiStoreMetadata("015", "Częstochowa", "ul. Okulickiego 16/18"),
    ObiStoreMetadata(
        "050",
        "Dąbrowa Górnicza",
        "ul. Jana III Sobieskiego 6a CH Pogoria",
    ),
    ObiStoreMetadata("073", "Gdańsk", "ul. Kołobrzeska 26"),
    ObiStoreMetadata("053", "Gdańsk", "ul. Przywidzka 6"),
    ObiStoreMetadata("008", "Gdańsk", "ul. Złota Karczma 26"),
    ObiStoreMetadata("004", "Gdynia", "ul. Kcyńska 27"),
    ObiStoreMetadata(
        "038",
        "Gorzów Wielkopolski",
        "ul. Myśliborska 48",
    ),
    ObiStoreMetadata("033", "Jabłonna", "ul. Zegrzyńska 9"),
    ObiStoreMetadata("044", "Jastrzębie Zdrój", "ul. Podhalańska 22"),
    ObiStoreMetadata("011", "Katowice", "ul. Rolna 4"),
    ObiStoreMetadata("069", "Kielce", "ul. Radomska 8"),
    ObiStoreMetadata("020", "Kielce", "ul. Zagnańska 67"),
    ObiStoreMetadata(
        "007",
        "Kobierzyce",
        "centrum „Bielany”, ul. Czekoladowa 5",
    ),
    ObiStoreMetadata(
        "037",
        "Kobylnica / k. Słupska",
        "ul. Szczecińska 8",
    ),
    ObiStoreMetadata("019", "Kraków", "Al. Bora-Komorowskiego 31"),
    ObiStoreMetadata("072", "Kraków", "Aleja Pokoju 67"),
    ObiStoreMetadata("003", "Kraków", "ul. Wielicka 259"),
    ObiStoreMetadata("059", "Kraków", "ul. Stawowa 68"),
    ObiStoreMetadata("051", "Leszno", "ul. Poznańska 5"),
    ObiStoreMetadata("061", "Lipienice", "ul. Podmiejska 2b"),
    ObiStoreMetadata(
        "074",
        "Łódź",
        "ul. Henryka Wieniawskiego 1/3",
    ),
    ObiStoreMetadata("071", "Łódź", "ul. Brzezińska 27/29"),
    ObiStoreMetadata("009", "Łódź", "ul. Rokicińska 192"),
    ObiStoreMetadata("030", "Łódź", "ul. Szparagowa 3/5"),
    ObiStoreMetadata("035", "Lubin", "ul. Zwierzyckiego 1"),
    ObiStoreMetadata("013", "Lublin", "ul. Chemiczna 2"),
    ObiStoreMetadata("036", "Lublin", "ul. Zwycięska 6"),
    ObiStoreMetadata("052", "Miejsce Piastowe", "ul. Handlowa 1"),
    ObiStoreMetadata("077", "Mysiadło", "ul. Kuropatwy 41"),
    ObiStoreMetadata(
        "075",
        "Nowy Sącz",
        "ul. Beliny Prażmowskiego 7",
    ),
    ObiStoreMetadata("029", "Olsztyn", "ul. Leonharda 1"),
    ObiStoreMetadata("070", "Olsztyn", "ul. Sikorskiego 2b"),
    ObiStoreMetadata("028", "Opole", "ul. Budowlanych 5"),
    ObiStoreMetadata(
        "060",
        "Ostrołęka",
        "ul. gen. Augusta Emila Fieldorfa \"Nila\" 32",
    ),
    ObiStoreMetadata("057", "Ostrów Wielkopolski", "ul. Kaliska 120"),
    ObiStoreMetadata(
        "016",
        "Piotrków Trybunalski",
        "ul. Sulejowska 51",
    ),
    ObiStoreMetadata("031", "Płock", "ul. Wyszogrodzka 142"),
    ObiStoreMetadata("041", "Poznań", "ul. Szwedzka 2"),
    ObiStoreMetadata("065", "Radom", "ul. Grzecznarowskiego 28"),
    ObiStoreMetadata("043", "Radom", "ul. Mireckiego 14"),
    ObiStoreMetadata("039", "Rybnik", "ul. Żorska 55"),
    ObiStoreMetadata("062", "Rzeszów", "ul. Podkarpacka 4"),
    ObiStoreMetadata("012", "Sochaczew", "ul. Wojtówka 2a"),
    ObiStoreMetadata("056", "Suwałki", "ul. Armii Krajowej 35"),
    ObiStoreMetadata("078", "Szczecin", "ul. Policka 11F"),
    ObiStoreMetadata("027", "Toruń", "ul. Szosa Lubicka 130"),
    ObiStoreMetadata("001", "Tychy", "Al. Bielska 109"),
    ObiStoreMetadata("049", "Tychy", "ul. Towarowa 2b"),
    ObiStoreMetadata("024", "Wałbrzych", "ul. Długa 1"),
    ObiStoreMetadata("002", "Warszawa", "al. Krakowska 102"),
    ObiStoreMetadata("017", "Warszawa", "ul. Puławska 427"),
    ObiStoreMetadata("018", "Warszawa", "ul. Radzymińska 166"),
    ObiStoreMetadata("048", "Włocławek", "ul. Cmentarna 2-6"),
    ObiStoreMetadata("040", "Wrocław", "ul. Długa 29-35"),
    ObiStoreMetadata("068", "Ząbki", "ul. Radzymińska 303"),
    ObiStoreMetadata(
        "055",
        "Zabrze",
        "Al. Wojciecha Korfantego 13",
    ),
    ObiStoreMetadata("076", "Zabrze", "ul. Szkubacza 1"),
)

internal val SUPPORTED_OBI_STORE_NUMBERS: List<String> =
    OBI_STORES.map(ObiStoreMetadata::storeNumber)

private val SUPPORTED_OBI_STORE_NUMBER_SET =
    SUPPORTED_OBI_STORE_NUMBERS.toSet()
private val OBI_STORE_NUMBER_PATTERN = Regex("""\d{3}""")
private val OBI_STORE_TOKEN_PATTERN = Regex("""(?<!\d)(\d{3})(?!\d)""")

internal fun isSupportedObiStoreNumber(
    storeNumber: String,
): Boolean =
    OBI_STORE_NUMBER_PATTERN.matches(storeNumber) &&
        storeNumber in SUPPORTED_OBI_STORE_NUMBER_SET

internal fun supportedObiStoreTokens(
    text: String,
): Set<String> =
    OBI_STORE_TOKEN_PATTERN
        .findAll(text)
        .map { it.groupValues[1] }
        .filter(::isSupportedObiStoreNumber)
        .toSet()
