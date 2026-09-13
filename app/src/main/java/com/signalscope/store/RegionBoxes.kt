package com.signalscope.store

import com.signalscope.collect.RegionPmtiles

/**
 * Country bounding boxes, as a static table in the APK.
 *
 * `map-regions.md` calls for "~250 entries, a few KB. No lookup service, works offline, no
 * dependency." This is that table: 239 countries with an ISO 3166-1 alpha-2 key — the same code
 * `TelephonyManager.getNetworkCountryIso()` returns — a display name, and a bbox in hundredths of
 * a degree (about 1.1 km, far finer than a country-scale box needs).
 *
 * Derived from Natural Earth 10m admin-0 (public domain) by taking each country's full extent
 * including its outlying territories, padded by 0.05°.
 *
 * ## The antimeridian is not an edge case
 *
 * Taking a naive min/max of longitude gives the United States, Russia, New Zealand, Fiji and
 * Kiribati a box spanning the entire planet, because each has territory on both sides of ±180°.
 * A "country context" download for the USA would then be a world download. The table is built by
 * measuring each country's extent twice — once in [−180,180) and once in [0,360) — and keeping
 * whichever is narrower, so **[west] may be greater than [east]**, meaning the box wraps. Every
 * consumer here and in [RegionPmtiles] handles that case explicitly.
 *
 * ## Zoom is budgeted, not fixed
 *
 * `map-regions.md` puts country context at z0–9 and estimates "low single-digit MB". That holds
 * for a small country and is badly wrong for a large one: Russia's bbox at z9 is roughly 22,000
 * tiles, which is a ~150 MB download, not 3 MB. So the zoom ceiling is chosen per country from a
 * tile budget instead of being a constant, and the UI states which ceiling a country got. A large
 * country gets coarser context; it does not get a surprise 150 MB.
 */
object RegionBoxes {

    data class Box(
        val iso: String,
        val name: String,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double
    ) {
        /** True when the box crosses ±180°, so the x tile range wraps. */
        val wraps: Boolean get() = west > east

        /**
         * Highest zoom at or below [ceiling] whose tile count for this box fits [budget].
         * Returns [floor] if even that overruns — a country context is always worth *something*.
         */
        fun zoomForBudget(ceiling: Int = 9, budget: Long = 3_500, floor: Int = 5): Int {
            for (z in ceiling downTo floor) {
                if (RegionPmtiles.tileCount(west, south, east, north, z) <= budget) return z
            }
            return floor
        }

        fun tilesTo(z: Int): Long =
            (0..z).sumOf { RegionPmtiles.tileCount(west, south, east, north, it) }
    }

    private val table: Map<String, Box> by lazy {
        val out = HashMap<String, Box>(320)
        for (row in RAW.replace("\n", "").split(';')) {
            val f = row.split('|')
            if (f.size != 6) continue
            out[f[0]] = Box(
                f[0], f[1],
                f[2].toInt() / 100.0, f[3].toInt() / 100.0,
                f[4].toInt() / 100.0, f[5].toInt() / 100.0
            )
        }
        out
    }

    /** Null for a country we have no box for — which is a reason to do nothing, not to guess. */
    fun of(iso: String?): Box? = iso?.uppercase()?.let { table[it] }

    fun name(iso: String?): String = of(iso)?.name ?: (iso?.uppercase() ?: "unknown")

    val size: Int get() = table.size

    /** ISO2|name|west|south|east|north in hundredths of a degree, ';'-separated. */
    private val RAW = """
AD|Andorra|136|4238|182|4270;AE|United Arab Emirates|5152|2257|5643|2612;AF|Afghanistan|6044|293
4|7494|3852;AG|Antigua and Barbuda|-6240|1688|-6162|1778;AI|Anguilla|-6348|1812|-6288|1865;AL|Al
bania|1922|3959|2109|4270;AM|Armenia|4339|3881|4665|4134;AO|Angola|1162|-1808|2411|-434;AQ|Antar
ctica|-3|-9000|-18|-6047;AR|Argentina|-7362|-5510|-5361|-2174;AS|American Samoa|-17114|-1458|-16
811|-1100;AT|Austria|947|4633|1720|4906;AU|Australia|9677|-5480|15916|-919;AW|Aruba|-7011|1237|-
6983|1268;AX|Åland|1946|5985|2115|6053;AZ|Azerbaijan|4472|3834|5068|4194;BA|Bosnia and Herzegovi
na|1567|4251|1967|4533;BB|Barbados|-5970|1300|-5938|1339;BD|Bangladesh|8797|2069|9269|2667;BE|Be
lgium|247|4945|642|5155;BF|Burkina Faso|-557|934|244|1513;BG|Bulgaria|2230|4119|2865|4428;BH|Bah
rain|5033|2553|5087|2634;BI|Burundi|2894|-451|3088|-225;BJ|Benin|71|616|389|1245;BL|Saint Barthé
lemy|-6292|1783|-6274|1798;BM|Bermuda|-6494|3220|-6460|3244;BN|Brunei|11395|397|11541|511;BO|Bol
ivia|-6972|-2295|-5742|-963;BR|Brazil|-7407|-3379|-2883|532;BS|The Bahamas|-7964|2086|-7270|2698
;BT|Bhutan|8868|2665|9214|2841;BW|Botswana|1993|-2694|2940|-1773;BY|Belarus|2312|5119|3277|5621;
BZ|Belize|-8929|1583|-8773|1854;CA|Canada|-14106|4162|-5257|8317;CD|Democratic Republic of the C
ongo|1216|-1351|3133|543;CF|Central African Republic|1434|219|2749|1105;CG|Republic of the Congo
|1106|-507|1869|376;CH|Switzerland|590|4577|1052|4785;CI|Ivory Coast|-867|429|-246|1078;CK|Cook 
Islands|-16587|-2199|-15726|-890;CL|Chile|-10950|-5597|-6637|-1746;CM|Cameroon|846|160|1626|1313
;CN|People's Republic of China|7355|1573|13482|5362;CO|Colombia|-8177|-429|-6683|1363;CR|Costa R
ica|-8717|547|-8251|1126;CU|Cuba|-8500|1978|-7408|2332;CV|Cape Verde|-2541|1475|-2262|1725;CW|Cu
raçao|-6922|1199|-6869|1244;CY|Cyprus|3222|3458|3415|3524;CZ|Czech Republic|1203|4851|1889|5109;
DE|Germany|580|4722|1507|5512;DJ|Djibouti|4170|1088|4347|1276;DK|Denmark|804|5452|1520|5780;DM|D
ominica|-6154|1515|-6120|1568;DO|Dominican Republic|-7206|1750|-6828|1999;DZ|Algeria|-873|1893|1
202|3714;EC|Ecuador|-9206|-506|-7518|171;EE|Estonia|2178|5747|2824|5972;EG|Egypt|2464|2194|3695|
3171;EH|Western Sahara|-1715|2072|-863|2771;ER|Eritrea|3637|1231|4317|1805;ES|Spain|-1822|2759|4
39|4384;ET|Ethiopia|3294|335|4803|1493;FI|Finland|2057|5976|3162|7013;FJ|Fiji|17454|-2176|-17817
|-1243;FK|Falkland Islands|-6137|-5246|-5768|-5098;FM|Federated States of Micronesia|13801|87|16
310|983;FO|Faroe Islands|-769|6134|-623|6245;FR|France|-10928|-2142|5590|5114;GA|Gabon|865|-399|
1455|237;GB|United Kingdom|-1374|4986|182|6090;GD|Grenada|-6184|1195|-6137|1258;GE|Georgia|3994|
4099|4674|4363;GG|Guernsey|-272|4936|-212|4978;GH|Ghana|-331|469|124|1121;GI|Gibraltar|-541|3606
|-529|3619;GL|Greenland|-7311|5974|-1133|8368;GM|The Gambia|-1688|1302|-1377|1387;GN|Guinea|-151
3|714|-761|1272;GQ|Equatorial Guinea|556|-153|1139|382;GR|Greece|1958|3477|2829|4180;GS|South Ge
orgia and the South Sandwich Islands|-3814|-5952|-2619|-5392;GT|Guatemala|-9230|1368|-8817|1787;
GU|Guam|14457|1319|14500|1370;GW|Guinea-Bissau|-1678|1088|-1361|1273;GY|Guyana|-6145|114|-5643|8
61;HK|Hong Kong|11379|2213|11445|2261;HM|Heard Island and McDonald Islands|7319|-5324|7386|-5291
;HN|Honduras|-8941|1293|-8308|1747;HR|Croatia|1345|4237|1946|4660;HT|Haiti|-7454|1798|-7159|2014
;HU|Hungary|1604|4569|2293|4862;ID|Indonesia|9496|-1097|14103|596;IE|Ireland|-1053|5140|-594|554
4;IL|Israel|3420|2944|3594|3346;IM|Isle of Man|-484|5401|-426|5447;IN|India|6809|670|9741|3555;I
O|British Indian Ocean Territory|7121|-748|7254|-518;IQ|Iraq|3872|2901|4861|3743;IR|Iran|4396|25
01|6337|3982;IS|Iceland|-2459|6335|-1345|6661;IT|Italy|655|3544|1857|4714;JE|Jersey|-229|4912|-1
96|4932;JM|Jamaica|-7842|1765|-7614|1858;JO|Jordan|3490|2914|3934|3342;JP|Japan|12289|2416|15404
|4557;KE|Kenya|3384|-473|4194|508;KG|Kyrgyzstan|6918|3914|8031|4331;KH|Cambodia|10226|1037|10766
|1475;KI|Kiribati|16947|-1151|-15173|477;KM|Comoros|4316|-1243|4458|-1131;KN|Saint Kitts and Nev
is|-6291|1705|-6249|1747;KP|North Korea|12416|3763|13075|4306;KR|South Korea|12456|3315|13191|38
67;KW|Kuwait|4648|2848|4848|3015;KY|Cayman Islands|-8147|1921|-7968|1981;KZ|Baikonur|4643|4053|8
737|5548;LA|Laos|10005|1387|10771|2255;LB|Lebanon|3505|3301|3665|3474;LC|Saint Lucia|-6113|1366|
-6083|1416;LI|Liechtenstein|943|4700|967|4731;LK|Sri Lanka|7961|587|8194|988;LR|Liberia|-1153|43
0|-733|862;LS|Lesotho|2695|-3071|2949|-2852;LT|Lithuania|2087|5384|2685|5649;LU|Luxembourg|566|4
939|655|5022;LV|Latvia|2092|5562|2827|5813;LY|Libya|924|1945|2521|3323;MA|Morocco|-1706|2137|-98
|3598;MC|Monaco|732|4367|749|4381;MD|Moldova|2657|4541|3018|4854;ME|Montenegro|1838|4180|2041|43
60;MF|Saint Martin|-6320|1798|-6296|1817;MG|Madagascar|4317|-2565|5055|-1189;MH|Marshall Islands
|16523|452|17208|1466;MK|North Macedonia|2039|4080|2306|4242;ML|Mali|-1231|1009|429|2505;MM|Myan
mar|9212|974|10122|2859;MN|Mongolia|8769|4154|11996|5218;MO|Macau|11347|2206|11364|2227;MP|North
ern Mariana Islands|14485|1406|14592|2061;MR|Mauritania|-1713|1468|-477|2734;MS|Montserrat|-6228
|1663|-6209|1687;MT|Malta|1413|3575|1462|3613;MU|Mauritius|5647|-2057|6354|-1027;MV|Maldives|726
3|-74|7380|716;MW|Malawi|3261|-1719|3595|-933;MX|Mexico|-11842|1450|-8665|3276;MY|Malaysia|9960|
80|11933|741;MZ|Mozambique|3016|-2691|4090|-1042;NA|Namibia|1167|-2901|2531|-1690;NC|New Caledon
ia|16357|-2272|17139|-1957;NE|Niger|10|1165|1602|2357;NF|Norfolk Island|16786|-2913|16805|-2895;
NG|Nigeria|262|422|1472|1393;NI|Nicaragua|-8774|1066|-8268|1508;NL|Netherlands|-6847|1197|725|53
61;NO|Norway|-917|-5451|3369|8082;NP|Nepal|7998|2629|8822|3047;NR|Nauru|16686|-60|16701|-44;NU|N
iue|-17000|-1919|-16973|-1891;NZ|New Zealand|16584|-5265|-17114|-849;OM|Oman|5193|1659|5989|2644
;PA|Panama|-8310|716|-7711|968;PE|Peru|-8139|-1839|-6863|2;PF|French Polynesia|-15459|-2769|-134
89|-790;PG|Papua New Guinea|14080|-1169|15602|-130;PH|Philippines|11690|461|12667|2117;PK|Pakist
an|6079|2364|7710|3710;PL|Poland|1407|4894|2419|5489;PM|Saint Pierre and Miquelon|-5645|4670|-56
09|4719;PN|Pitcairn Islands|-13080|-2513|-12473|-2387;PR|Puerto Rico|-6799|1787|-6519|1857;PS|Pa
lestine|3415|3116|3562|3259;PT|Portugal|-3133|2998|-616|4220;PW|Palau|13108|290|13478|815;PY|Par
aguay|-6270|-2764|-5420|-1924;QA|Qatar|5070|2451|5167|2621;RO|Romania|2019|4360|2975|4832;RS|Ser
bia|1879|4218|2303|4622;RU|Russia|1956|4114|-16894|8191;RW|Rwanda|2881|-288|3094|-101;SA|Saudi A
rabia|3452|1632|5569|3217;SB|Solomon Islands|15546|-1234|16888|-655;SC|Seychelles|4616|-981|5634
|-374;SD|Sudan|2176|863|3865|2228;SE|Sweden|1106|5529|2421|6909;SG|Singapore|10359|121|10405|150
;SH|Saint Helena|-1447|-4045|-560|-783;SI|Slovenia|1332|4537|1657|4691;SK|Slovakia|1679|4770|225
9|4965;SL|Sierra Leone|-1335|687|-1023|1005;SM|San Marino|1234|4384|1254|4403;SN|Senegal|-1759|1
226|-1133|1674;SO|Somalia|4092|-175|5147|1204;SR|Suriname|-5812|178|-5394|606;SS|South Sudan|240
7|344|3597|1227;ST|São Tomé and Príncipe|641|-3|751|175;SV|El Salvador|-9016|1311|-8764|1450;SX|
Sint Maarten|-6317|1797|-6297|1811;SY|Syria|3567|3226|4243|3737;SZ|Eswatini|3073|-2737|3217|-256
9;TC|Turks and Caicos Islands|-7253|2124|-7108|2201;TD|Chad|1340|741|2403|2349;TF|French Souther
n and Antarctic Lands|3968|-4977|7764|-1150;TG|Togo|-22|605|183|1118;TH|Thailand|9730|558|10570|
2050;TJ|Tajikistan|6729|3663|7521|4109;TL|East Timor|12398|-955|12736|-809;TM|Turkmenistan|5239|
3509|6670|4284;TN|Tunisia|743|3018|1161|3740;TO|Tonga|-17627|-2239|-17386|-1551;TR|Turkey|2561|3
577|4486|4215;TT|Trinidad and Tobago|-6198|999|-6047|1140;TV|Tuvalu|17608|-947|17996|-563;TW|Tai
wan|11823|2185|12206|2534;TZ|Tanzania|2927|-1178|4050|-94;UA|Ukraine|2208|4516|4021|5242;UG|Ugan
da|2950|-153|3506|427;UM|United States Minor Outlying Islands|16657|-44|-7495|2827;US|United Sta
tes of America|17243|1886|-6693|7146;UY|Uruguay|-5849|-3502|-5306|-3005;UZ|Uzbekistan|5593|3714|
7320|4561;VA|Vatican City|1240|4185|1250|4195;VC|Saint Vincent and the Grenadines|-6151|1254|-61
07|1343;VE|Venezuela|-7344|60|-5977|1575;VG|British Virgin Islands|-6482|1828|-6422|1880;VI|Unit
ed States Virgin Islands|-6509|1763|-6451|1844;VN|Vietnam|10207|852|10952|2342;VU|Vanuatu|16647|
-2030|16995|-1301;WF|Wallis and Futuna|-17824|-1437|-17608|-1316;WS|Samoa|-17283|-1410|-17139|-1
341;XK|Kosovo|1997|4179|2182|4331;YE|Yemen|4250|1206|5459|1905;ZA|South Africa|1642|-4702|3803|-
2208;ZM|Zambia|2193|-1812|3372|-814;ZW|Zimbabwe|2517|-2245|3309|-1556""".trimIndent()
}
