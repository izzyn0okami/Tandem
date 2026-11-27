package app.lawnchair.gamemode

data class GamePlatform(
    val slug: String,
    val name: String,
    val coreId: String,
    val extensions: Set<String>,
    val folderAliases: Set<String>,
    val sortOrder: Int,
    /** Identificador do console no RetroAchievements (null = sem conquistas). */
    val raConsoleId: Int? = null,
    /** Jogos em disco (cue/chd/iso...): podem vir como pasta com vários arquivos. */
    val discBased: Boolean = false,
    /** O arquivo ZIP é a própria ROM (ex.: conjuntos arcade do Neo Geo), não um pacote a extrair. */
    val zipIsNativeRom: Boolean = false,
    /** Arquivos que acompanham a faixa de dados de um disco e não são jogos independentes. */
    val discCompanionExtensions: Set<String> = emptySet(),
    /** Arquivos de BIOS alternativos: basta um deles quando não há grupos explícitos. */
    val bios: List<BiosFile> = emptyList(),
    /**
     * Grupos obrigatórios de BIOS: todos os grupos precisam ser satisfeitos, mas basta um arquivo
     * presente dentro de cada grupo. Ex.: regiões alternativas formam um grupo; arquivos
     * simultâneos do mesmo núcleo ficam em grupos separados.
     */
    val biosGroups: List<List<BiosFile>> = emptyList(),
    /** true = sem BIOS o jogo não roda; false = só melhora a compatibilidade. */
    val biosRequired: Boolean = false,
    /** Dentro do Tandem (núcleo libretro) ou em outro app. */
    val launch: LaunchMode = LaunchMode.LIBRETRO,
    /** Núcleos que não usam um arquivo de BIOS só, e sim uma pasta inteira de arquivos de sistema (ex.: blueMSX). Nomes das subpastas esperadas dentro da pasta de BIOS. */
    val biosKit: List<String>? = null,
    /**
     * Jogo pode vir em vários arquivos sem ser "em disco" (ex.: Switch com jogo base + atualização + DLC,
     * cada um um .nsp/.xci separado). Baixa a pasta inteira (como os jogos em disco), mas sem gerar .m3u:
     * abre o arquivo principal (entre os que não parecem atualização/DLC pelo nome, o de nome mais curto)
     * e deixa o resto do lado, para o emulador externo instalar por conta própria.
     */
    val multiFileFolder: Boolean = false,
)

/** Um arquivo de BIOS esperado na pasta de sistema do núcleo. */
enum class BiosLocation { SYSTEM, SAVES }

data class BiosFile(
    val name: String,
    val label: String,
    val md5Hash: String? = null,
    val location: BiosLocation = BiosLocation.SYSTEM,
)

/**
 * Plataformas que o motor atual (M3) sabe jogar. A decisão segue a lógica do Argosy: o nome da
 * pasta manda quando existe; sem pasta reconhecida, vale a extensão do arquivo (se for única).
 */
object GamePlatforms {

    val all = listOf(
        GamePlatform(
            "nes",
            "Nintendo Entertainment System",
            "fceumm",
            setOf("nes", "unf", "unif", "fds"),
            setOf("nes", "famicom", "fc"),
            10,
            7,
            bios = listOf(
                BiosFile("disksys.rom", "Famicom Disk System (obrigatório para jogos FDS)", "ca30b50f880eb660a320674ed365ef7a"),
                BiosFile("gamegenie.nes", "Game Genie (opcional)", "7f98d77d7a094ad7d069b74bd553ec98"),
            ),
        ),
        GamePlatform("snes", "Super Nintendo", "snes9x", setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "superfamicom", "supernintendo"), 20, 3),
        GamePlatform(
            "gb",
            "Game Boy",
            "mgba",
            setOf("gb"),
            setOf("gb", "gameboy"),
            30,
            4,
            bios = listOf(BiosFile("gb_bios.bin", "Game Boy BIOS (opcional)", "32fbbd84168d3482956eb3c5051637f5")),
        ),
        GamePlatform(
            "gbc",
            "Game Boy Color",
            "mgba",
            setOf("gbc"),
            setOf("gbc", "gameboycolor"),
            31,
            6,
            bios = listOf(BiosFile("gbc_bios.bin", "Game Boy Color BIOS (opcional)", "dbfce9db9deaa2567f6a84fde55f9680")),
        ),
        GamePlatform(
            slug = "gba",
            name = "Game Boy Advance",
            coreId = "mgba",
            extensions = setOf("gba"),
            folderAliases = setOf("gba", "gameboyadvance"),
            sortOrder = 32,
            raConsoleId = 5,
            bios = listOf(BiosFile("gba_bios.bin", "Game Boy Advance (opcional)", "a860e8c0b6d573d191e4ec7db1b1e4f6")),
        ),
        GamePlatform(
            "genesis",
            "Mega Drive / Genesis",
            "genesis_plus_gx",
            setOf("md", "gen", "smd"),
            setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive"),
            40,
            1,
            bios = listOf(BiosFile("bios_MD.bin", "Mega Drive boot ROM (opcional)", "45e298905a08f9cfb38fd504cd6dbc84")),
        ),
        GamePlatform(
            "sms",
            "Master System",
            "genesis_plus_gx",
            setOf("sms"),
            setOf("sms", "mastersystem", "segamastersystem"),
            41,
            11,
            bios = listOf(
                BiosFile("bios_E.sms", "Master System Europa (opcional)", "840481177270d5642a14ca71ee72844c"),
                BiosFile("bios_U.sms", "Master System EUA (opcional)", "840481177270d5642a14ca71ee72844c"),
                BiosFile("bios_J.sms", "Master System Japão (opcional)", "24a519c53f67b00640d0048ef7089105"),
            ),
        ),
        GamePlatform(
            "gg",
            "Game Gear",
            "genesis_plus_gx",
            setOf("gg"),
            setOf("gg", "gamegear", "segagamegear"),
            42,
            15,
            bios = listOf(BiosFile("bios.gg", "Game Gear BIOS (opcional)", "672e104c3be3a238301aceffc3b23fd6")),
        ),
        GamePlatform("32x", "Sega 32X", "picodrive", setOf("32x"), setOf("32x", "sega32x"), 43),
        GamePlatform(
            slug = "scd",
            name = "Sega CD",
            coreId = "genesis_plus_gx",
            extensions = setOf("cue", "chd", "iso", "m3u"),
            folderAliases = setOf("scd", "segacd", "megacd", "segamegacd"),
            sortOrder = 44,
            discBased = true,
            discCompanionExtensions = setOf("bin", "wav"),
            bios = listOf(
                BiosFile("bios_CD_U.bin", "Sega CD (EUA)", "854b9150240a198070150e4566ae1290"),
                BiosFile("bios_CD_E.bin", "Mega CD (Europa)", "e66fa1dc5820d254611fdcdba0662372"),
                BiosFile("bios_CD_J.bin", "Mega CD (Japão)", "278a9397d192149e84e820ac621a8edd"),
            ),
            biosRequired = true,
        ),
        GamePlatform("n64", "Nintendo 64", "mupen64plus_next_gles3", setOf("z64", "n64", "v64"), setOf("n64", "nintendo64"), 50),
        GamePlatform(
            slug = "psx",
            name = "PlayStation",
            coreId = "pcsx_rearmed",
            extensions = setOf("cue", "chd", "pbp", "iso", "m3u"),
            folderAliases = setOf("psx", "ps1", "playstation", "playstation1", "sonyplaystation"),
            sortOrder = 60,
            discBased = true,
            discCompanionExtensions = setOf("bin", "img", "sub", "sbi", "ccd", "wav"),
            bios = listOf(
                BiosFile("scph5501.bin", "PlayStation (EUA) v3.0", "490f666e1afb15b7362b406ed1cea246"),
                BiosFile("scph5500.bin", "PlayStation (Japão)", "8dd7d5296a650fac7319bce665a6a53c"),
                BiosFile("scph5502.bin", "PlayStation (Europa)", "32736f17079d0b2b7024407c39bd3050"),
                BiosFile("scph1001.bin", "PlayStation (EUA) v2.0", "924e392ed05558ffdb115408c263dccf"),
                BiosFile("scph101.bin", "PlayStation (EUA) v4.4", "6e3735ff4c7dc899ee98981385f6f3d0"),
                BiosFile("scph7001.bin", "PlayStation (EUA) v4.1", "1e68c231d0896b7eadcad1d7d8e76129"),
                BiosFile("PSXONPSP660.bin", "PlayStation (PSP, região livre)", "c53ca5908936d412331790f4426c6c33"),
            ),
        ),
        GamePlatform("pce", "PC Engine / TurboGrafx-16", "mednafen_pce_fast", setOf("pce"), setOf("pce", "tg16", "pcengine", "turbografx16", "turbografx"), 70, 8),
        GamePlatform("atari2600", "Atari 2600", "stella", setOf("a26"), setOf("atari2600", "2600", "a26"), 80, 25),
        GamePlatform("ngp", "Neo Geo Pocket", "mednafen_ngp", setOf("ngp", "ngc"), setOf("ngp", "ngpc", "neogeopocket", "neogeopocketcolor"), 90, 14),
        GamePlatform("wswan", "WonderSwan", "mednafen_wswan", setOf("ws", "wsc"), setOf("wonderswan", "wonderswancolor", "wswan", "ws", "wsc"), 91, 53),
        GamePlatform("sg1000", "Sega SG-1000", "genesis_plus_gx", setOf("sg"), setOf("sg1000", "segasg1000"), 47, 33),
        GamePlatform(
            slug = "saturn",
            name = "Sega Saturn",
            coreId = "mednafen_saturn",
            extensions = setOf("cue", "chd", "iso", "m3u", "ccd"),
            folderAliases = setOf("saturn", "segasaturn"),
            sortOrder = 45,
            discBased = true,
            discCompanionExtensions = setOf("bin", "img", "sub", "wav"),
            bios = listOf(
                BiosFile("sega_101.bin", "Saturn (Japão)", "85ec9ca47d8f6807718151cbcca8b964"),
                BiosFile("mpr-17933.bin", "Saturn (EUA/Europa)", "3240872c70984b6cbfda1586cab68dbe"),
            ),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "dreamcast",
            name = "Dreamcast",
            coreId = "flycast",
            extensions = setOf("gdi", "cdi", "chd", "cue", "m3u"),
            folderAliases = setOf("dreamcast", "dc", "segadreamcast"),
            sortOrder = 46,
            discBased = true,
            discCompanionExtensions = setOf("bin", "raw", "wav"),
            bios = listOf(
                BiosFile("dc/dc_boot.bin", "Dreamcast (boot ROM)", "e10c53c2f8b90bab96ead2d368858623"),
                BiosFile("dc/dc_flash.bin", "Dreamcast (flash)"),
            ),
        ),
        GamePlatform(
            slug = "nds",
            name = "Nintendo DS",
            coreId = "melonds_ds",
            extensions = setOf("nds", "dsi", "ids"),
            folderAliases = setOf("nds", "ds", "nintendods", "dsi"),
            sortOrder = 33,
            bios = listOf(
                BiosFile("bios7.bin", "DS ARM7 (opcional)", "df692a80a5b1bc90728bc3dfc76cd948"),
                BiosFile("bios9.bin", "DS ARM9 (opcional)", "a392174eb3e572fed6447e956bde4b25"),
                BiosFile("firmware.bin", "DS firmware (opcional)"),
                BiosFile("dsi_bios7.bin", "DSi ARM7 (opcional)"),
                BiosFile("dsi_bios9.bin", "DSi ARM9 (opcional)"),
                BiosFile("dsi_firmware.bin", "DSi firmware (opcional)"),
                BiosFile("dsi_nand.bin", "DSi NAND (opcional)"),
                BiosFile("dsi_sd_card.bin", "DSi cartão SD (opcional)"),
            ),
        ),
        GamePlatform(
            slug = "psp",
            name = "PlayStation Portable",
            coreId = "ppsspp",
            extensions = setOf("iso", "cso", "pbp"),
            folderAliases = setOf("psp", "playstationportable", "sonypsp"),
            sortOrder = 61,
            discBased = true,
        ),
        GamePlatform(
            slug = "3do",
            name = "3DO",
            coreId = "opera",
            extensions = setOf("iso", "chd", "cue"),
            folderAliases = setOf("3do", "panasonic3do"),
            sortOrder = 62,
            discBased = true,
            discCompanionExtensions = setOf("bin"),
            bios = listOf(
                BiosFile("panafz10.bin", "3DO Panasonic FZ-10", "51f2f43ae2f3508a14d9f56597e2d3ce"),
                BiosFile("panafz1.bin", "3DO Panasonic FZ-1", "f47264dd47fe30f73ab3c010015c155b"),
                BiosFile("panafz10-norsa.bin", "3DO Panasonic FZ-10 (RSA Patch)", "1477bda80dc33731a65468c1f5bcbee9"),
                BiosFile("panafz10e-anvil.bin", "3DO Panasonic FZ-10-E Anvil", "a48e6746bd7edec0f40cff078f0bb19f"),
                BiosFile("panafz10e-anvil-norsa.bin", "3DO Panasonic FZ-10-E Anvil (RSA Patch)", "cf11bbb5a16d7af9875cca9de9a15e09"),
                BiosFile("panafz1j.bin", "3DO Panasonic FZ-1J", "a496cfdded3da562759be3561317b605"),
                BiosFile("panafz1j-norsa.bin", "3DO Panasonic FZ-1J (RSA Patch)", "f6c71de7470d16abe4f71b1444883dc8"),
                BiosFile("goldstar.bin", "3DO Goldstar GDO-101M", "8639fd5e549bd6238cfee79e3e749114"),
                BiosFile("sanyotry.bin", "3DO Sanyo IMP-21J TRY", "35fa1a1ebaaeea286dc5cd15487c13ea"),
                BiosFile("3do_arcade_saot.bin", "3DO Arcade Shootout At Old Tucson", "8970fc987ab89a7f64da9f8a8c4333ff"),
            ),
            biosRequired = true,
        ),
        GamePlatform("vb", "Virtual Boy", "mednafen_vb", setOf("vb", "vboy"), setOf("vb", "virtualboy", "vboy"), 92, 28),
        GamePlatform(
            slug = "atari7800",
            name = "Atari 7800",
            coreId = "prosystem",
            extensions = setOf("a78"),
            folderAliases = setOf("atari7800", "7800", "a78"),
            sortOrder = 81,
            raConsoleId = 51,
            bios = listOf(BiosFile("7800 BIOS (U).rom", "Atari 7800 (EUA, opcional)", "0763f1ffb006ddbe32e52d497ee848ae")),
        ),
        GamePlatform(
            slug = "atari5200",
            name = "Atari 5200",
            coreId = "a5200",
            extensions = setOf("a52"),
            folderAliases = setOf("atari5200", "5200", "a52"),
            sortOrder = 82,
            bios = listOf(BiosFile("5200.rom", "Atari 5200 (opcional)", "281f20ea4320404ec820fb7ec0693b38")),
            biosRequired = false,
        ),
        GamePlatform(
            slug = "lynx",
            name = "Atari Lynx",
            coreId = "handy",
            extensions = setOf("lnx"),
            folderAliases = setOf("lynx", "atarilynx", "lnx"),
            sortOrder = 83,
            raConsoleId = 13,
            bios = listOf(BiosFile("lynxboot.img", "Atari Lynx (obrigatório)", "fcd403db69f54290b51035d82f835e7b")),
            biosRequired = true,
        ),
        GamePlatform("jaguar", "Atari Jaguar", "virtualjaguar", setOf("j64", "jag"), setOf("jaguar", "atarijaguar", "j64"), 84),
        GamePlatform(
            slug = "ps2",
            name = "PlayStation 2",
            coreId = "armsx2",
            extensions = setOf("elf", "iso", "chd", "cso", "zso", "bin", "mdf", "nrg", "gz", "img", "m3u"),
            folderAliases = setOf("ps2", "playstation2", "sonyplaystation2"),
            sortOrder = 100,
            discBased = true,
            discCompanionExtensions = setOf("bin", "img", "sub"),
            biosRequired = true,
            biosKit = listOf("pcsx2/bios", "pcsx2/resources"),
        ),
        GamePlatform(
            slug = "gc",
            name = "GameCube",
            coreId = "dolphin",
            extensions = setOf("gcm", "iso", "gcz", "rvz", "ciso", "tgc", "m3u"),
            folderAliases = setOf("gc", "ngc", "gamecube", "nintendogamecube"),
            sortOrder = 101,
            discBased = true,
            bios = listOf(
                BiosFile(
                    "dolphin-emu/User/GC/USA/IPL.bin",
                    "GameCube IPL dos EUA (opcional)",
                    location = BiosLocation.SAVES,
                ),
            ),
            biosGroups = listOf(
                listOf(BiosFile("dolphin-emu/Sys/codehandler.bin", "Dolphin Sys codehandler (obrigatório)")),
            ),
            biosRequired = true,
            biosKit = listOf("dolphin-emu/Sys"),
        ),
        GamePlatform(
            slug = "wii",
            name = "Wii",
            coreId = "dolphin",
            extensions = setOf("wbfs", "iso", "rvz", "wia", "wad", "m3u"),
            folderAliases = setOf("wii", "nintendowii"),
            sortOrder = 102,
            discBased = true,
            biosGroups = listOf(
                listOf(BiosFile("dolphin-emu/Sys/codehandler.bin", "Dolphin Sys codehandler (obrigatório)")),
            ),
            biosRequired = true,
            biosKit = listOf("dolphin-emu/Sys"),
        ),
        GamePlatform(
            slug = "switch",
            name = "Nintendo Switch",
            coreId = "",
            extensions = setOf("nsp", "xci", "nsz", "xcz"),
            folderAliases = setOf("switch", "nintendoswitch", "ns"),
            sortOrder = 103,
            launch = LaunchMode.EMULATOR,
            multiFileFolder = true,
        ),
        GamePlatform(
            slug = "3ds",
            name = "Nintendo 3DS",
            coreId = "azahar",
            extensions = setOf("3ds", "cci", "cxi", "cia", "3dsx", "app", "elf", "axf"),
            folderAliases = setOf("3ds", "n3ds", "nintendo3ds"),
            sortOrder = 104,
            bios = listOf(
                BiosFile(
                    "Citra/sysdata/aes_keys.txt",
                    "Chaves AES do Nintendo 3DS (opcional para jogos criptografados)",
                    location = BiosLocation.SAVES,
                ),
            ),
        ),
        GamePlatform(
            slug = "wiiu",
            name = "Wii U",
            coreId = "cemu",
            extensions = setOf("wua", "wud", "wux", "iso", "rpx", "elf"),
            folderAliases = setOf("wiiu", "nintendowiiu"),
            sortOrder = 105,
            bios = listOf(BiosFile("Cemu/keys.txt", "Chaves de disco do Wii U (opcional)")),
            multiFileFolder = true,
        ),
        GamePlatform(
            slug = "ps3",
            name = "PlayStation 3",
            coreId = "rpcs3",
            extensions = setOf("iso", "pkg", "ps3", "elf", "self", "m3u"),
            folderAliases = setOf("ps3", "playstation3", "sonyplaystation3"),
            sortOrder = 106,
            discBased = true,
            discCompanionExtensions = setOf("bin", "key", "dkey", "rap", "edat"),
            bios = listOf(BiosFile("rpcs3/PS3UPDAT.PUP", "Firmware do PlayStation 3 (obrigatório)")),
            biosRequired = true,
            multiFileFolder = true,
        ),
        GamePlatform(
            slug = "vita",
            name = "PlayStation Vita",
            coreId = "vita3k",
            extensions = setOf("vpk", "zip", "pkg", "bin"),
            folderAliases = setOf("vita", "psvita", "psv", "playstationvita", "sonypsvita"),
            sortOrder = 107,
            zipIsNativeRom = true,
            bios = listOf(
                BiosFile("vita3k/PSP2UPDAT.PUP", "Firmware do PS Vita (obrigatório)"),
                BiosFile("vita3k/PSP2UPDAT_PREINST.PUP", "Pacote pré-instalado do firmware PS Vita (obrigatório)"),
                BiosFile("vita3k/PSP2UPDAT_FONT.PUP", "Fontes do firmware PS Vita (opcional)"),
            ),
            biosGroups = listOf(
                listOf(BiosFile("vita3k/PSP2UPDAT.PUP", "Firmware do PS Vita")),
                listOf(BiosFile("vita3k/PSP2UPDAT_PREINST.PUP", "Pacote pré-instalado do PS Vita")),
            ),
            biosRequired = true,
            multiFileFolder = true,
        ),
        GamePlatform(
            slug = "xbox360",
            name = "Xbox 360",
            coreId = "xenia_edge",
            extensions = setOf("iso", "xex", "zar", "xcp", "x360", "m3u"),
            folderAliases = setOf("xbox360", "xbox 360", "x360", "microsoftxbox360"),
            sortOrder = 108,
            discBased = true,
            multiFileFolder = true,
        ),
        GamePlatform(
            slug = "model3",
            name = "Sega Model 3",
            coreId = "supermodel",
            extensions = setOf("zip"),
            folderAliases = setOf("model3", "sega_model3", "sega model 3", "sega model3"),
            sortOrder = 109,
            zipIsNativeRom = true,
            bios = listOf(
                BiosFile("supermodel/Config/Games.xml", "Catálogo de jogos Supermodel (obrigatório)"),
                BiosFile("supermodel/Config/Supermodel.ini", "Configuração Supermodel (obrigatória)"),
            ),
            biosGroups = listOf(
                listOf(BiosFile("supermodel/Config/Games.xml", "Games.xml")),
                listOf(BiosFile("supermodel/Config/Supermodel.ini", "Supermodel.ini")),
            ),
            biosRequired = true,
            biosKit = listOf("supermodel/Config"),
        ),
        GamePlatform(
            slug = "pc",
            name = "PC (GameNative)",
            coreId = "",
            extensions = setOf("steam", "gog", "epic", "amazon"),
            folderAliases = setOf("pc", "windows", "gamenative", "steam", "pcgames"),
            sortOrder = 110,
            launch = LaunchMode.GAMENATIVE,
        ),
        GamePlatform(
            slug = "coleco",
            name = "ColecoVision",
            coreId = "gearcoleco",
            extensions = setOf("col"),
            folderAliases = setOf("coleco", "colecovision", "col"),
            sortOrder = 85,
            raConsoleId = 44,
            bios = listOf(BiosFile("colecovision.rom", "ColecoVision", "2c66f5911e5b42b8ebe113403548eee7")),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "msx",
            name = "MSX / MSX2",
            coreId = "bluemsx",
            extensions = setOf("rom", "mx1", "mx2", "dsk", "cas"),
            folderAliases = setOf("msx", "msx1", "msx2", "msx-msx2"),
            sortOrder = 87,
            raConsoleId = 29,
            biosRequired = true,
            biosKit = listOf("Databases", "Machines"),
        ),
        GamePlatform(
            slug = "intellivision",
            name = "Intellivision",
            coreId = "freeintv",
            extensions = setOf("int", "bin", "rom"),
            folderAliases = setOf("intellivision", "inty", "int"),
            sortOrder = 86,
            raConsoleId = 45,
            bios = listOf(
                BiosFile("exec.bin", "Intellivision (Executive)", "62e761035cb657903761800f4437b8af"),
                BiosFile("grom.bin", "Intellivision (GROM)", "0cd5946c6473e42e8e4c2137785e427f"),
            ),
            biosGroups = listOf(
                listOf(BiosFile("exec.bin", "Intellivision (Executive)")),
                listOf(BiosFile("grom.bin", "Intellivision (GROM)")),
            ),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "neogeo",
            name = "Neo Geo (MVS/AES)",
            coreId = "fbneo",
            extensions = setOf("zip"),
            folderAliases = setOf("neogeo", "neogeoaes", "neogeomvs", "neo-geo", "mvs", "aes"),
            sortOrder = 88,
            discBased = false,
            zipIsNativeRom = true,
            bios = listOf(BiosFile("neogeo.zip", "Neo Geo (BIOS do conjunto)")),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "easyrpg",
            name = "EasyRPG (RPG Maker 2000/2003)",
            coreId = "easyrpg",
            extensions = setOf("ldb", "zip", "lzh", "easyrpg"),
            folderAliases = setOf("easyrpg", "rpgmaker", "rpgmaker2k", "rpgmaker2000", "rpgmaker2003", "rpgmaker2k3"),
            sortOrder = 99,
        ),
    )

    /** Extensões genéricas: só valem quando a pasta já diz a plataforma. */
    val genericExtensions = setOf("zip", "bin")

    private fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Plataforma do nosso motor equivalente ao identificador de plataforma do RomM. */
    fun fromRomM(slug: String?): GamePlatform? {
        val key = normalize(slug.orEmpty())
        if (key.isEmpty()) return null
        return all.firstOrNull { key in it.folderAliases || key == normalize(it.slug) }
            ?: fuzzy(key)
    }

    /** Nomes longos que o RomM pode usar (ex.: "super-nintendo-entertainment-system"). */
    private fun fuzzy(key: String): GamePlatform? = when {
        "megadrive" in key || "genesis" in key -> bySlug("genesis")
        "gameboyadvance" in key -> bySlug("gba")
        "gameboycolor" in key -> bySlug("gbc")
        "gameboy" in key -> bySlug("gb")
        "supernintendo" in key || "superfamicom" in key || "snes" in key -> bySlug("snes")
        "nintendoentertainment" in key || "famicom" in key -> bySlug("nes")
        else -> null
    }

    fun bySlug(slug: String): GamePlatform? = all.firstOrNull { it.slug == slug }

    /** [folders] são as pastas do arquivo, da mais próxima para a mais distante da raiz. */
    fun resolve(folders: List<String>, extension: String): GamePlatform? {
        val ext = extension.lowercase()
        for (folder in folders) {
            val key = normalize(folder)
            val byFolder = all.firstOrNull { key in it.folderAliases }
            if (byFolder != null) {
                return byFolder.takeIf {
                    ext in it.extensions || (!it.discBased && ext in genericExtensions)
                }
            }
        }
        return all.singleOrNull { ext in it.extensions }
    }
}
