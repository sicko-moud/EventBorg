import { activeChannels } from "./util.js";

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function channels(env, lang) {
  const da = lang === "da";
  const c = activeChannels(env);
  const rows = [];
  if (c.ig) rows.push(`Instagram: <a href="https://www.instagram.com/${esc(c.ig)}/">@${esc(c.ig)}</a> (${da ? "direkte besked" : "direct message"})`);
  if (c.wa) rows.push(`WhatsApp: <a href="https://wa.me/${esc(c.wa.slice(1))}">${esc(c.wa)}</a>`);
  rows.push(da ? "EventBorg-appen: del en tekst, et link eller et billede til appen" : "The EventBorg app: share a text, link or image to the app");
  return `<ul>${rows.map((r) => `<li>${r}</li>`).join("")}</ul>`;
}

// Live message channels as words ("an Instagram DM to @x or our WhatsApp number"), or "" when none is live.
function ways(env, lang) {
  const c = activeChannels(env), da = lang === "da";
  const parts = [];
  if (c.ig) parts.push(da ? `en Instagram-besked til @${esc(c.ig)}` : `an Instagram DM to @${esc(c.ig)}`);
  if (c.wa) parts.push(da ? `vores WhatsApp-nummer ${esc(c.wa)}` : `our WhatsApp number ${esc(c.wa)}`);
  return parts.join(da ? " eller " : " or ");
}

function contact(env, lang) {
  const issues = `<a href="https://github.com/${esc(env.GH_REPO)}/issues">GitHub issues</a>`;
  const mail = env.CONTACT_EMAIL ? ` ${lang === "da" ? "eller" : "or"} <a href="mailto:${esc(env.CONTACT_EMAIL)}">${esc(env.CONTACT_EMAIL)}</a>` : "";
  return issues + mail;
}

const PAGES = {
  privacy: {
    en: (env) => ({
      title: "Privacy policy",
      body: `
<p>EventBorg is a free, independent event listing for Sønderborg Municipality. It is <strong>not</strong> an official app of Sønderborg Kommune. There are no user accounts, no ads, no analytics and no tracking.</p>
<h2>The app</h2>
<ul>
<li>Saved events, favourites and settings stay on your phone.</li>
<li>The event list is downloaded from GitHub Pages. Like any web server, GitHub sees your IP address when the file is fetched; we receive nothing.</li>
<li>Notifications use Firebase Cloud Messaging (Google). Your phone subscribes to topics such as a category or a venue. Google handles the device token; we never see it and cannot tell who subscribes.</li>
<li>Optional story and group detection reads Instagram and Facebook notifications <em>on your phone only</em>, and only those from profiles and groups you chose to watch or from venues listed in the app; every other notification is left alone. Story notifications are never stored or sent, and your watch list never leaves your phone. If you also turn on sharing, the app sends only a listed venue's id and the time.</li>
<li>When a venue or organisation listed in the app (public accounts only) posts something new, your phone sends that notification's preview text and picture to our server, which asks Google Gemini whether it is an event; nothing about you or your account goes with it, and the picture is not stored. Posts from profiles you added yourself never leave your phone. You can turn this off in Settings.</li>
<li>For Facebook groups you watch, the app keeps a short preview of posts that look like events on your phone for 7 days. Nothing from a group leaves your phone unless you tap "Add as event", which sends that preview to be read like any text you share with the app.</li>
</ul>
<h2>When you send us an event</h2>
<ul>
<li>Text, links and images you share with the app go to our Cloudflare Worker and to the Google Gemini API, which reads out the event details. Images are not stored. If you submit the result, the event details are kept until they are published or rejected (at most 14 days). Published events are public.</li>
<li>Google's free Gemini tier may use submitted content to improve Google's products, so do not share private content.</li>
${ways(env, "en") ? `<li>Messages to ${ways(env, "en")} are handled through Meta's official APIs. We keep only a salted hash of your sender id, the message text with names and phone numbers removed, and links to the media (never the media itself), for at most 14 days, plus a hashed counter of how many of your events were published. So that we can answer you once, your sender id is also kept encrypted, readable only by our server, for at most 24 hours. We reply at most once per message.</li>` : ""}
<li>Events that need a manual check are reviewed in a GitHub issue that contains only the event details.</li>
<li>"Suggest a source" sends the name, link and note you type; they are posted as a public GitHub issue for us to check. Nothing about you or your phone is included.</li>
<li>Stories are never downloaded, read or analysed.${activeChannels(env).ig ? ` When a listed venue mentions @${esc(env.IG_HANDLE)} in its story, we only note which venue and when.` : ""}</li>
</ul>
<h2>Processors</h2>
<ul>
<li>Meta Platforms (Instagram, Facebook, WhatsApp) — reading listed venues' public Instagram posts, and your messages if you write to us</li>
<li>Google (Firebase Cloud Messaging, Gemini API) — notifications and reading event details</li>
<li>Cloudflare (Workers, KV) — our small server; IP addresses are used for rate limiting only and are not stored by us</li>
<li>GitHub (Actions, Pages, Issues) — collecting, publishing and reviewing events</li>
</ul>
<h2>Your rights</h2>
<p>You can have your data deleted at any time; see <a href="/data-deletion">data deletion</a>. Questions: ${contact(env, "en")}.</p>`,
    }),
    da: (env) => ({
      title: "Privatlivspolitik",
      body: `
<p>EventBorg er en gratis, uafhængig oversigt over arrangementer i Sønderborg Kommune. Det er <strong>ikke</strong> en officiel app fra Sønderborg Kommune. Der er ingen brugerkonti, ingen reklamer, ingen statistik og ingen sporing.</p>
<h2>Appen</h2>
<ul>
<li>Gemte arrangementer, favoritter og indstillinger bliver på din telefon.</li>
<li>Listen over arrangementer hentes fra GitHub Pages. Som enhver webserver ser GitHub din IP-adresse, når filen hentes; vi modtager intet.</li>
<li>Notifikationer sendes med Firebase Cloud Messaging (Google). Din telefon abonnerer på emner, fx en kategori eller et spillested. Google håndterer enhedens token; vi ser det aldrig og kan ikke se, hvem der abonnerer.</li>
<li>Den valgfri genkendelse af stories og gruppeopslag læser Instagram- og Facebook-notifikationer <em>kun på din telefon</em>, og kun dem fra profiler og grupper, du selv har valgt at følge, eller fra steder i appen; alle andre notifikationer lader appen være. Story-notifikationer gemmes eller sendes aldrig, og din liste over fulgte profiler forlader aldrig telefonen. Hvis du også slår deling til, sender appen kun et steds id og tidspunktet.</li>
<li>Når et sted eller en arrangør fra appen (kun offentlige konti) lægger noget nyt op, sender din telefon notifikationens forhåndstekst og billede til vores server, som spørger Google Gemini, om det er et arrangement; intet om dig eller din konto sendes med, og billedet gemmes ikke. Opslag fra profiler, du selv har tilføjet, forlader aldrig telefonen. Du kan slå det fra under Indstillinger.</li>
<li>For Facebook-grupper, du følger, gemmer appen en kort forhåndsvisning af opslag, der ligner arrangementer, på din telefon i 7 dage. Intet fra en gruppe forlader telefonen, medmindre du trykker "Tilføj som arrangement", som sender forhåndsvisningen til udlæsning ligesom tekst, du deler med appen.</li>
</ul>
<h2>Når du sender os et arrangement</h2>
<ul>
<li>Tekst, links og billeder, du deler med appen, sendes til vores Cloudflare Worker og til Google Gemini API, som læser detaljerne ud. Billeder gemmes ikke. Hvis du indsender resultatet, gemmes detaljerne, indtil de er offentliggjort eller afvist (højst 14 dage). Offentliggjorte arrangementer er offentlige.</li>
<li>Googles gratis Gemini-niveau kan bruge indsendt indhold til at forbedre Googles produkter, så del ikke privat indhold.</li>
${ways(env, "da") ? `<li>Beskeder til ${ways(env, "da")} håndteres via Metas officielle API'er. Vi gemmer kun en saltet hash af dit afsender-id, beskedteksten uden navne og telefonnumre og links til medierne (aldrig selve medierne) i højst 14 dage, samt en hashet tæller over, hvor mange af dine arrangementer der er offentliggjort. For at kunne svare dig én gang gemmer vi også dit afsender-id krypteret, så kun vores server kan læse det, i højst 24 timer. Vi svarer højst én gang pr. besked.</li>` : ""}
<li>Arrangementer, der skal tjekkes manuelt, gennemgås i et GitHub-issue, der kun indeholder arrangementets detaljer.</li>
<li>"Foreslå en kilde" sender det navn, link og den bemærkning, du skriver; de lægges op som et offentligt GitHub-issue, som vi tjekker. Intet om dig eller din telefon er med.</li>
<li>Stories bliver aldrig downloadet, læst eller analyseret.${activeChannels(env).ig ? ` Når et sted i appen nævner @${esc(env.IG_HANDLE)} i sin story, noterer vi kun hvilket sted og hvornår.` : ""}</li>
</ul>
<h2>Databehandlere</h2>
<ul>
<li>Meta Platforms (Instagram, Facebook, WhatsApp) — læsning af stedernes offentlige Instagram-opslag og dine beskeder, hvis du skriver til os</li>
<li>Google (Firebase Cloud Messaging, Gemini API) — notifikationer og udlæsning af detaljer</li>
<li>Cloudflare (Workers, KV) — vores lille server; IP-adresser bruges kun til begrænsning af forespørgsler og gemmes ikke af os</li>
<li>GitHub (Actions, Pages, Issues) — indsamling, offentliggørelse og gennemgang af arrangementer</li>
</ul>
<h2>Dine rettigheder</h2>
<p>Du kan altid få dine data slettet; se <a href="/data-deletion?lang=da">sletning af data</a>. Spørgsmål: ${contact(env, "da")}.</p>`,
    }),
  },
  "data-deletion": {
    en: (env) => ({
      title: "Data deletion",
      body: `
<p>EventBorg stores very little about you (see the <a href="/privacy">privacy policy</a>): a salted hash of your sender id, your queued messages for at most 14 days, and a hashed count of your published events.</p>
<h2>Delete your data</h2>
${ways(env, "en") ? `<ol>
<li>Send the word <strong>DELETE</strong> (or <strong>SLET</strong>) to us in the same place you contacted us: ${ways(env, "en")}.</li>
<li>Within 24 hours we remove all your queued items and your trust record, and reply to confirm.</li>
</ol>` : "<p>We take no messages right now, so we hold no sender data. Events submitted from the app carry no sender id.</p>"}
<p>If you remove the EventBorg app from your Facebook or Instagram settings, Meta tells us and we delete your data the same way.</p>
<p>Published events are public event information. To have an event changed or removed, write to ${contact(env, "en")}.</p>
<p>The app itself keeps everything on your phone: clear its storage or uninstall it to remove it.</p>`,
    }),
    da: (env) => ({
      title: "Sletning af data",
      body: `
<p>EventBorg gemmer meget lidt om dig (se <a href="/privacy?lang=da">privatlivspolitikken</a>): en saltet hash af dit afsender-id, dine beskeder i kø i højst 14 dage og en hashet tæller over dine offentliggjorte arrangementer.</p>
<h2>Slet dine data</h2>
${ways(env, "da") ? `<ol>
<li>Send ordet <strong>SLET</strong> (eller <strong>DELETE</strong>) til os samme sted, som du kontaktede os: ${ways(env, "da")}.</li>
<li>Inden for 24 timer fjerner vi alle dine beskeder i kø og din tillidsregistrering og svarer for at bekræfte.</li>
</ol>` : "<p>Vi modtager ingen beskeder lige nu og har derfor ingen afsenderdata. Arrangementer indsendt fra appen har intet afsender-id.</p>"}
<p>Hvis du fjerner EventBorg-appen i dine Facebook- eller Instagram-indstillinger, får vi besked fra Meta og sletter dine data på samme måde.</p>
<p>Offentliggjorte arrangementer er offentlig information. Skriv til ${contact(env, "da")} for at få et arrangement rettet eller fjernet.</p>
<p>Selve appen gemmer alt på din telefon: ryd appens data eller afinstallér den for at fjerne det.</p>`,
    }),
  },
  organizers: {
    en: (env) => ({
      title: "For organizers",
      body: `
<p>EventBorg lists public events in Sønderborg Municipality for free. Here is how your events get in.</p>
<h2>1. From your website (best)</h2>
<ul>
<li>Use an events calendar that publishes <a href="https://schema.org/Event">schema.org Event</a> data, an iCal feed, or WordPress "The Events Calendar". Most event plugins do this out of the box.</li>
<li>Then <a href="https://github.com/${esc(env.GH_REPO)}/issues/new?title=New%20source">suggest your site</a> or use "Suggest a source" in the app. We check robots.txt and fetch politely, at most once an hour.</li>
</ul>
<h2>2. Sønderborg Kommune's calendar</h2>
<p>Events in the municipality's <a href="https://sonderborg.dk/begivenheder/">event calendar</a> are picked up automatically.</p>
<h2>3. Send it to us</h2>
${channels(env, "en")}
<p>Forward a post, a flyer, a screenshot or a link. Clear posts appear within minutes; others are checked by hand first.${ways(env, "en") ? " Once three of your events have been published, yours are added straight away." : ""}</p>
<h2>4. Stories</h2>
<p>App users can watch your Instagram or Facebook profile. When the notification for your new story reaches their phone, EventBorg shows a "might be an event" alert that opens your story. For listed venues, users who opt in share that alert with everyone who follows you in the app.${activeChannels(env).ig ? ` You can also mention @${esc(env.IG_HANDLE)} in your story.` : ""} We never download or read stories.</p>
<h2>What we list</h2>
<p>Public events with a date, inside the municipality. Not ads, opening hours or private events. Questions: ${contact(env, "en")}.</p>`,
    }),
    da: (env) => ({
      title: "For arrangører",
      body: `
<p>EventBorg viser gratis offentlige arrangementer i Sønderborg Kommune. Sådan kommer dine arrangementer med.</p>
<h2>1. Fra jeres hjemmeside (bedst)</h2>
<ul>
<li>Brug en kalender, der udgiver <a href="https://schema.org/Event">schema.org Event</a>-data, et iCal-feed eller WordPress "The Events Calendar". De fleste event-plugins gør det automatisk.</li>
<li><a href="https://github.com/${esc(env.GH_REPO)}/issues/new?title=Ny%20kilde">Foreslå jeres side</a> eller brug "Foreslå en kilde" i appen. Vi respekterer robots.txt og henter højst én gang i timen.</li>
</ul>
<h2>2. Sønderborg Kommunes kalender</h2>
<p>Arrangementer i kommunens <a href="https://sonderborg.dk/begivenheder/">begivenhedskalender</a> kommer automatisk med.</p>
<h2>3. Send det til os</h2>
${channels(env, "da")}
<p>Videresend et opslag, en flyer, et skærmbillede eller et link. Tydelige opslag kommer med inden for få minutter; andre tjekkes først manuelt.${ways(env, "da") ? " Når tre af jeres arrangementer er offentliggjort, kommer de næste med med det samme." : ""}</p>
<h2>4. Stories</h2>
<p>Brugere af appen kan følge jeres Instagram- eller Facebook-profil. Når notifikationen om jeres nye story når deres telefon, viser EventBorg en "måske et arrangement"-besked, der åbner jeres story. For steder i appen kan brugere, der har slået det til, dele beskeden med alle, der følger jer i appen.${activeChannels(env).ig ? ` I kan også nævne @${esc(env.IG_HANDLE)} i jeres story.` : ""} Vi downloader eller læser aldrig stories.</p>
<h2>Hvad vi viser</h2>
<p>Offentlige arrangementer med en dato i kommunen. Ikke reklamer, åbningstider eller private arrangementer. Spørgsmål: ${contact(env, "da")}.</p>`,
    }),
  },
};

const CSS = `:root{color-scheme:light dark;--fg:#1b1b1f;--bg:#fdfcff;--mu:#5d5e67;--ac:#3a5ba9}
@media(prefers-color-scheme:dark){:root{--fg:#e4e2e6;--bg:#1b1b1f;--mu:#a9a9b3;--ac:#b1c5ff}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,sans-serif}
main{max-width:42rem;margin:0 auto;padding:1.5rem 1rem 3rem}a{color:var(--ac)}
h1{font-size:1.6rem;margin:.2rem 0 1rem}h2{font-size:1.1rem;margin-top:1.6rem}
nav{display:flex;gap:1rem;flex-wrap:wrap;font-size:.9rem;color:var(--mu)}li{margin:.3rem 0}`;

function shell(lang, title, body, path) {
  const other = lang === "da" ? ["en", "English"] : ["da", "Dansk"];
  const nav = [["privacy", lang === "da" ? "Privatliv" : "Privacy"], ["data-deletion", lang === "da" ? "Sletning" : "Data deletion"],
    ["organizers", lang === "da" ? "Arrangører" : "Organizers"]]
    .map(([p, n]) => `<a href="/${p}${lang === "da" ? "?lang=da" : ""}">${n}</a>`).join("");
  return `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} · EventBorg</title><style>${CSS}</style></head><body><main>
<nav><strong>EventBorg</strong>${nav}<a href="${path}?lang=${other[0]}" hreflang="${other[0]}">${other[1]}</a></nav>
<h1>${esc(title)}</h1>${body}</main></body></html>`;
}

export function renderPage(path, url, req, env) {
  const key = path === "/" ? "organizers" : path.slice(1);
  const page = PAGES[key];
  if (!page) return null;
  const q = url.searchParams.get("lang");
  const lang = q === "da" || q === "en" ? q : /^da\b/i.test(req.headers.get("accept-language") || "") ? "da" : "en";
  const { title, body: main } = page[lang](env);
  const code = key === "data-deletion" ? (url.searchParams.get("code") || "").replace(/[^A-Za-z0-9]/g, "").slice(0, 32) : "";
  const body = code
    ? `<p><strong>${lang === "da" ? `Anmodning ${code} er modtaget. Dine beskeder i kø er slettet, og din tillidsregistrering slettes inden for 24 timer.` : `Request ${code} received. Your queued messages are deleted and your trust record is removed within 24 hours.`}</strong></p>${main}`
    : main;
  return new Response(req.method === "HEAD" ? null : shell(lang, title, body, `/${key}`), {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=3600", vary: "accept-language" },
  });
}
