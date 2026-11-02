/**
 * Julkinen tietosuojaseloste. Palvelin tarjoilee sen, jotta kaupoilla ja
 * sovelluksella on pysyvä osoite. Sivu toimii ilman skriptejä, evästeitä ja ulkoisia resursseja.
 */
export const UPDATED = "2026-11-01";
const EMAIL = "jrs8205@pm.me";
const DPA = "https://www.cloudflare.com/dpa/";
const AUTHORITY = "https://tietosuoja.fi";

export type Language = "fi" | "sv" | "en";
const LANGUAGES: readonly Language[] = ["fi", "sv", "en"];

interface Text {
  name: string;
  title: string;
  updated: string;
  sections: Array<{ heading: string; body?: string[]; table?: { head: string[]; rows: string[][] }; after?: string[] }>;
}

const contact = `jrs8205, <a href="mailto:${EMAIL}">${EMAIL}</a>`;

const TEXTS: Record<Language, Text> = {
  fi: {
    name: "Suomi",
    title: "Työmatka: tietosuojaseloste",
    updated: "Päivitetty",
    sections: [
      { heading: "Rekisterinpitäjä ja yhteystieto", body: [`Sovelluksen kehittäjä ${contact}.`] },
      {
        heading: "Lyhyesti",
        body: [
          "Työmatka on ilmainen ajon suunnittelu-, navigointi- ja työajosovellus. Sovelluksessa ei ole tiliä, mainoksia eikä analytiikkaa. Sijaintia käytetään taustalla vain ajon aikana, jolloin puhelimessa näkyy pysyvä ilmoitus. Stopit, työpäivät ja tankkaukset pysyvät puhelimessasi. Verkkoon lähtee vain se, mitä kartta, haku ja reititys tarvitsevat.",
        ],
      },
      {
        heading: "Tiedot, jotka pysyvät puhelimessa",
        body: [
          "Stopit ja reitit, osoitemuisti, työpäivät, mittarilukemat, tankkaukset ja niiden kuvat, kirjoittamasi lisätiedot, omat ajoneuvot, stoppien väliset ajoajat ja pysähdysten kestot, muistutusten asetukset ja valittujen Bluetooth-laitteiden osoitteet. Sijaintia käytetään, kun sovellus on käytössä tai ajo on käynnissä; ajettua reittiä ei tallenneta.",
          "Tiedot eivät mene Googlen pilvivarmuuskopioon. Voit viedä ne itse tiedostoksi asetuksista, ja tiedosto on silloin sinun hallinnassasi. Sovelluksen poistaminen poistaa tiedot puhelimesta, mutta ei palvelimelle lähetettyjä ilmoituksia.",
          "Jos stoppi on asiakkaan osoite, huolehdi itse siitä, että saat käsitellä sitä. Sovelluksen raportit ovat apuväline: vastaat itse kirjanpidosta ja siitä, täyttävätkö ne esimerkiksi Verohallinnon vaatimukset.",
        ],
      },
      {
        heading: "Tiedot, jotka lähtevät verkkoon",
        body: ["Jokainen palvelu näkee puhelimen IP-osoitteen, ellei toisin kerrota."],
        table: {
          head: ["Palvelu", "Tarkoitus", "Mitä lähtee"],
          rows: [
            ["Valhalla ja OSRM, FOSSGIS e.V. (valhalla1.openstreetmap.de, routing.openstreetmap.de)", "Reititys ja navigointi", "Nykyinen sijainti lähtöpisteenä ja stoppien koordinaatit sekä sovelluksen nimi ja versio. Sijainti lähtee myös ajon aikana reitin päivittyessä ja kohdekortin ajoaikaa laskettaessa. Palvelin kirjaa reittipyynnöt lokiinsa."],
            ["OpenFreeMap (tiles.openfreemap.org)", "Karttaruudut", "Katsottu kartta-alue"],
            ["Sovelluksen oma palvelin, Cloudflare (poi.jrs8205.com)", "Karttakohteet, yritys- ja osoitehaku", "Kartta-alue, hakuteksti, noin kilometrin tarkkuudella pyöristetty sijainti ja valitun kohteen piste. Palvelin ei tallenna hakuja eikä IP-osoitteita; IP-osoitetta käytetään hetkellisesti pyyntömäärän rajoittamiseen."],
            ["Digitransit, HSL (palvelimen kautta)", "Osoitehaku", "Hakuteksti, noin kilometrin tarkkuudella pyöristetty sijainti ja valitun kohteen piste. IP-osoitteesi ei välity Digitransitille."],
            ["Photon, komoot (photon.komoot.io)", "Varahaku, jos Digitransit ei vastaa tai ei löydä mitään", "Hakuteksti, noin kilometrin tarkkuudella pyöristetty sijainti ja valitun kohteen piste"],
          ],
        },
        after: ["Osoitteet, jotka lisäät stopeiksi, lähtevät siis reitityspalveluun koordinaatteina."],
      },
      {
        heading: "Ilmoitukset karttakohteista",
        body: [
          "Jos ilmoitat kohteesta korjauksen, palvelimelle tallentuu kohde, ilmoitettu tieto, tekohetki ja satunnainen laitetunnus. IP-osoitetta ei tallenneta. Ilmoitus näkyy muille ilman tunnusta. Laitetunnus poistuu ilmoituksesta 12 kuukauden kuluttua, minkä jälkeen ilmoitus säilyy ilman yhteyttä laitteeseen. Voit poistaa tämän laitteen tunnuksella tehdyt ilmoitukset asetuksista. Sovellus saa silloin uuden tunnuksen, ja vanha tunnus säilyy palvelimella enintään 7 vuorokautta ilman ilmoituksia, jottei matkalla oleva ilmoitus tallennu sillä. Jos poistat sovelluksen tai sen tiedot, tunnus katoaa, joten poista ilmoitukset sitä ennen.",
        ],
      },
      {
        heading: "Käsittelyn peruste",
        body: [
          "Sovelluksen toimintojen tarjoaminen pyynnöstäsi (kartta, haku, reititys) ja oikeutettu etu: karttakohteiden tiedot pidetään ajan tasalla (ilmoitukset). Sijaintilupa on puhelimessa hallittavissa, ja voit perua sen milloin vain.",
        ],
      },
      {
        heading: "Tiedon siirto EU:n ulkopuolelle",
        body: [
          `Cloudflare on yhdysvaltalainen yritys ja toimii maailmanlaajuisesti, joten pyyntö voi kulkea EU:n ulkopuolisen palvelimen kautta. Cloudflare käyttää siirtoihin EU:n ja Yhdysvaltojen välistä Data Privacy Framework -järjestelyä ja tarvittaessa EU:n vakiolausekkeita (<a href="${DPA}">Cloudflaren tietojenkäsittelyehdot</a>).`,
        ],
      },
      {
        heading: "Oikeutesi",
        body: [
          `Sinulla on oikeus saada tietoa käsittelystä, pyytää tiedon oikaisua tai poistoa ja vastustaa käsittelyä. Koska tiliä ei ole, ilmoituksia ei voi yhdistää sinuun ilman laitetunnusta: helpoin tapa on poisto asetuksista. Voit tehdä valituksen tietosuojavaltuutetulle (<a href="${AUTHORITY}">tietosuoja.fi</a>).`,
        ],
      },
      { heading: "Muutokset", body: ["Muutokset näkyvät tällä sivulla päivämäärän kanssa."] },
    ],
  },
  sv: {
    name: "Svenska",
    title: "Körjournal: integritetspolicy",
    updated: "Uppdaterad",
    sections: [
      { heading: "Personuppgiftsansvarig och kontakt", body: [`Appens utvecklare ${contact}.`] },
      {
        heading: "I korthet",
        body: [
          "Körjournal (Työmatka) är en kostnadsfri app för att planera körningar, navigera och föra körjournal. Appen har inget konto, inga annonser och ingen analys. Platsen används i bakgrunden bara under körning, och då visas en permanent avisering i telefonen. Stopp, arbetsdagar och tankningar stannar i din telefon. Bara det som kartan, sökningen och ruttberäkningen behöver skickas över nätet.",
        ],
      },
      {
        heading: "Uppgifter som stannar i telefonen",
        body: [
          "Stopp och rutter, adressminnet, arbetsdagar, mätarställningar, tankningar och deras bilder, tilläggsuppgifter du skrivit, egna fordon, körtider mellan stopp och stoppens längd, påminnelseinställningar och adresserna till valda Bluetooth-enheter. Platsen används när appen är i bruk eller en körning pågår; den körda rutten sparas inte.",
          "Uppgifterna säkerhetskopieras inte till Googles moln. Du kan själv exportera dem till en fil i inställningarna, och då har du kontroll över filen. När appen tas bort raderas uppgifterna från telefonen, men inte rapporter som skickats till servern.",
          "Om ett stopp är en kunds adress ansvarar du själv för att du får behandla den. Appens rapporter är ett hjälpmedel: du ansvarar själv för bokföringen och för om de uppfyller till exempel Skatteförvaltningens krav.",
        ],
      },
      {
        heading: "Uppgifter som skickas över nätet",
        body: ["Varje tjänst ser telefonens IP-adress, om inget annat anges."],
        table: {
          head: ["Tjänst", "Ändamål", "Vad som skickas"],
          rows: [
            ["Valhalla och OSRM, FOSSGIS e.V. (valhalla1.openstreetmap.de, routing.openstreetmap.de)", "Ruttberäkning och navigering", "Nuvarande plats som startpunkt och stoppens koordinater samt appens namn och version. Platsen skickas också under körning när rutten uppdateras och när körtiden för ett objekt beräknas. Servern loggar ruttförfrågningar."],
            ["OpenFreeMap (tiles.openfreemap.org)", "Kartrutor", "Det kartområde du tittar på"],
            ["Appens egen server, Cloudflare (poi.jrs8205.com)", "Kartobjekt, företags- och adressökning", "Kartområde, söktext, plats avrundad till ungefär en kilometer och det valda objektets punkt. Servern sparar varken sökningar eller IP-adresser; IP-adressen används tillfälligt för att begränsa antalet förfrågningar."],
            ["Digitransit, HSL (via servern)", "Adressökning", "Söktext, plats avrundad till ungefär en kilometer och det valda objektets punkt. Din IP-adress vidarebefordras inte till Digitransit."],
            ["Photon, komoot (photon.komoot.io)", "Reservsökning om Digitransit inte svarar eller inte hittar något", "Söktext, plats avrundad till ungefär en kilometer och det valda objektets punkt"],
          ],
        },
        after: ["Adresser som du lägger till som stopp skickas alltså till ruttjänsten som koordinater."],
      },
      {
        heading: "Rapporter om kartobjekt",
        body: [
          "Om du rapporterar en rättelse om ett objekt sparas objektet, den rapporterade uppgiften, tidpunkten och en slumpmässig enhetskod på servern. IP-adressen sparas inte. Rapporten visas för andra utan koden. Enhetskoden tas bort från rapporten efter 12 månader, och därefter finns rapporten kvar utan koppling till enheten. Du kan radera rapporter som gjorts med den här enhetens kod i inställningarna. Appen får då en ny kod, och den gamla koden finns kvar på servern i högst 7 dygn utan rapporter, så att en rapport på väg inte sparas med den. Om du tar bort appen eller dess data försvinner koden, så radera rapporterna innan dess.",
        ],
      },
      {
        heading: "Grund för behandlingen",
        body: [
          "Att tillhandahålla appens funktioner på din begäran (karta, sökning, ruttberäkning) och berättigat intresse av att hålla kartobjektens uppgifter aktuella (rapporter). Platsbehörigheten styr du i telefonen och kan när som helst återkalla.",
        ],
      },
      {
        heading: "Överföring utanför EU",
        body: [
          `Cloudflare är ett amerikanskt företag som verkar globalt, så en förfrågan kan gå via en server utanför EU. Cloudflare använder EU:s och USA:s Data Privacy Framework och vid behov EU:s standardavtalsklausuler för överföringar (<a href="${DPA}">Cloudflares villkor för databehandling</a>).`,
        ],
      },
      {
        heading: "Dina rättigheter",
        body: [
          `Du har rätt att få information om behandlingen, begära rättelse eller radering och invända mot behandlingen. Eftersom det inte finns något konto kan rapporter inte kopplas till dig utan enhetskoden: det enklaste är att radera dem i inställningarna. Du kan lämna in ett klagomål till dataombudsmannen (<a href="${AUTHORITY}">tietosuoja.fi</a>).`,
        ],
      },
      { heading: "Ändringar", body: ["Ändringar visas på den här sidan med datum."] },
    ],
  },
  en: {
    name: "English",
    title: "Work Trip: privacy policy",
    updated: "Updated",
    sections: [
      { heading: "Controller and contact", body: [`The app's developer ${contact}.`] },
      {
        heading: "In short",
        body: [
          "Work Trip (Työmatka) is a free app for planning drives, navigating and keeping a driving log. It has no account, no ads and no analytics. Location is used in the background only during a drive, when an ongoing notification is shown on the phone. Stops, working days and fill-ups stay on your phone. Only what the map, search and routing need goes over the network.",
        ],
      },
      {
        heading: "Data that stays on the phone",
        body: [
          "Stops and routes, saved addresses, working days, odometer readings, fill-ups and their photos, additional notes you write, your vehicles, driving times between stops and stop durations, reminder settings and the addresses of selected Bluetooth devices. Location is used while the app is in use or a drive is under way; the driven route is not stored.",
          "The data is not included in Google's cloud backup. You can export it to a file in the settings, and that file is then under your control. Uninstalling the app deletes the data from the phone, but not reports sent to the server.",
          "If a stop is a customer's address, it is your responsibility to make sure you may process it. The app's reports are a tool: you are responsible for your bookkeeping and for whether they meet, for example, the requirements of the Finnish Tax Administration.",
        ],
      },
      {
        heading: "Data that goes over the network",
        body: ["Each service sees the phone's IP address unless stated otherwise."],
        table: {
          head: ["Service", "Purpose", "What is sent"],
          rows: [
            ["Valhalla and OSRM, FOSSGIS e.V. (valhalla1.openstreetmap.de, routing.openstreetmap.de)", "Routing and navigation", "Current location as the start point and stop coordinates, and the app's name and version. Location is also sent during a drive when the route updates and when the driving time to a place is calculated. The server logs route requests."],
            ["OpenFreeMap (tiles.openfreemap.org)", "Map tiles", "The map area you view"],
            ["The app's own server, Cloudflare (poi.jrs8205.com)", "Map places, business and address search", "Map area, search text, location rounded to about a kilometre and the point of the selected place. The server stores neither searches nor IP addresses; the IP address is used momentarily to limit the request rate."],
            ["Digitransit, HSL (through the server)", "Address search", "Search text, location rounded to about a kilometre and the point of the selected place. Your IP address is not passed on to Digitransit."],
            ["Photon, komoot (photon.komoot.io)", "Fallback search if Digitransit does not answer or finds nothing", "Search text, location rounded to about a kilometre and the point of the selected place"],
          ],
        },
        after: ["Addresses you add as stops are therefore sent to the routing service as coordinates."],
      },
      {
        heading: "Reports about map places",
        body: [
          "If you report a correction to a place, the server stores the place, the reported information, the time and a random device code. The IP address is not stored. The report is shown to others without the code. The device code is removed from the report after 12 months, after which the report remains with no link to the device. You can delete reports made with this device's code in the settings. The app then gets a new code, and the old code stays on the server for at most 7 days without any reports, so that a report still on its way is not stored with it. If you uninstall the app or clear its data, the code is lost, so delete your reports first.",
        ],
      },
      {
        heading: "Legal basis",
        body: [
          "Providing the app's functions at your request (map, search, routing) and the legitimate interest of keeping map place data up to date (reports). You control the location permission on the phone and can withdraw it at any time.",
        ],
      },
      {
        heading: "Transfers outside the EU",
        body: [
          `Cloudflare is a US company that operates globally, so a request may pass through a server outside the EU. For transfers Cloudflare uses the EU-U.S. Data Privacy Framework and, where needed, the EU standard contractual clauses (<a href="${DPA}">Cloudflare's data processing terms</a>).`,
        ],
      },
      {
        heading: "Your rights",
        body: [
          `You have the right to information about the processing, to ask for correction or erasure and to object to the processing. As there is no account, reports cannot be linked to you without the device code: the easiest way is to delete them in the settings. You can lodge a complaint with the Finnish Data Protection Ombudsman (<a href="${AUTHORITY}">tietosuoja.fi</a>).`,
        ],
      },
      { heading: "Changes", body: ["Changes are shown on this page with their date."] },
    ],
  },
};

const STYLE =
  "body{font:16px/1.5 system-ui,sans-serif;margin:0 auto;max-width:46rem;padding:1rem;color:#111;background:#fff}" +
  "h1{font-size:1.5rem}h2{font-size:1.15rem;margin-top:1.75rem}table{border-collapse:collapse;width:100%}" +
  "th,td{border:1px solid #777;padding:.4rem;text-align:left;vertical-align:top;overflow-wrap:anywhere}a{color:#0b4ea2}" +
  "@media(prefers-color-scheme:dark){body{color:#eee;background:#121212}a{color:#a0c4ff}th,td{border-color:#888}}";

function render(language: Language): string {
  const text = TEXTS[language];
  const links = LANGUAGES.map((l) => (l === language ? `<strong>${TEXTS[l].name}</strong>` : `<a href="/privacy?lang=${l}" hreflang="${l}">${TEXTS[l].name}</a>`)).join(" · ");
  const paragraphs = (items: string[] = []) => items.map((p) => `<p>${p}</p>`).join("");
  const sections = text.sections
    .map((s) => {
      const table = s.table
        ? `<table><thead><tr>${s.table.head.map((h) => `<th scope="col">${h}</th>`).join("")}</tr></thead><tbody>${s.table.rows
            .map((row) => `<tr>${row.map((c) => `<td>${c}</td>`).join("")}</tr>`)
            .join("")}</tbody></table>`
        : "";
      return `<h2>${s.heading}</h2>${paragraphs(s.body)}${table}${paragraphs(s.after)}`;
    })
    .join("");
  return `<!doctype html><html lang="${language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${text.title}</title><style>${STYLE}</style></head><body><nav>${links}</nav><h1>${text.title}</h1><p>${text.updated}: ${UPDATED}</p>${sections}</body></html>`;
}

/** Kieli parametrista, sitten otsakkeesta; muuten englanti, koska kauppojen tarkastajat lukevat sitä. */
export function pickLanguage(requested: string | null, header: string | null): Language {
  const asked = LANGUAGES.find((l) => l === requested);
  if (asked) return asked;
  const first = (header ?? "").split(",").map((part) => part.trim().split(/[-;]/)[0].toLowerCase());
  return first.map((code) => LANGUAGES.find((l) => l === code)).find((l) => l !== undefined) ?? "en";
}

export function privacyPage(url: URL, request: Request): Response {
  const language = pickLanguage(url.searchParams.get("lang"), request.headers.get("Accept-Language"));
  return new Response(render(language), {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Content-Language": language,
      Vary: "Accept-Language",
      "Cache-Control": "public, max-age=3600",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
