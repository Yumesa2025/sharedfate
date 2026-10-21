import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SITE = resolve(HERE, '..');
const ROOT = resolve(SITE, '..');
const DIST = resolve(SITE, 'dist');

const read = (path) => readFileSync(resolve(DIST, path), 'utf-8');
const json = (path) => JSON.parse(readFileSync(resolve(ROOT, path), 'utf-8'));

const perks = json('src/main/resources/sharedfate-perks-default.json').perks;
const sets = json('src/main/resources/sharedfate-sets-default.json').sets;
// 시련 카드는 아직 JSON 이 아니라 코드에 있다. 이름만 뽑아 쓴다.
const trialSource = readFileSync(resolve(ROOT, 'src/main/java/com/sharedfate/sync/TrialCatalog.java'), 'utf-8');
const trialNames = [...trialSource.matchAll(/new Trial\("[^"]+",\s*"([^"]+)"/g)].map((match) => match[1]);

const problems = [];
const check = (ok, message) => { if (!ok) problems.push(message); };

const countOf = (text, needle) => text.split(needle).length - 1;

// 1. 증강 브라우저에 정의 파일과 같은 수의 카드가 있다.
const perkPage = read('perks/index.html');
const cards = countOf(perkPage, 'class="perk"');
check(cards === perks.length, `증강 카드가 ${cards}장이다. 정의는 ${perks.length}개다.`);

// 1-1. 드래곤전 대비(분류) 카드가 정의 파일의 수만큼 있고, 그 이름이 페이지에 찍혀 있다.
//      정의 파일에 아직 하나도 없으면 0장으로 통과한다.
const trialPerks = perks.filter((perk) => perk.rarity === 'trial').length;
const trialCards = countOf(perkPage, 'data-rarity="trial"');
check(trialCards === trialPerks, `드래곤전 대비 카드가 ${trialCards}장이다. 정의는 ${trialPerks}개다.`);
check(perkPage.includes('드래곤전 대비'), '증강 페이지에 「드래곤전 대비」 표식이 없다.');

// 2. 세트 페이지에 유형이 모두 있다.
const setPage = read('sets/index.html');
const sections = countOf(setPage, '<section id=');
check(sections === sets.length, `세트 절이 ${sections}개다. 정의는 ${sets.length}개다.`);

// 3. 이름이 실제로 찍혀 있다 — 빈 카드가 아니다.
for (const perk of [perks[0], perks[perks.length - 1]]) {
  check(perkPage.includes(perk.name), `증강 「${perk.name}」 이 목록에 없다.`);
}

// 4. 가이드 여섯이 다 나왔고 본문이 채워져 있다.
for (const tab of ['install', 'start', 'perks', 'sets', 'economy', 'commands']) {
  try {
    const page = read(`guide/${tab}/index.html`);
    check(!page.includes('준비 중입니다'), `가이드 ${tab} 이 아직 빈 채다.`);
  } catch {
    problems.push(`가이드 ${tab} 이 안 나왔다.`);
  }
}

// 4-1. 드래곤 시련 페이지가 나왔고 카드 이름이 코드와 맞는다.
let trialCount = 0;
try {
  const trialPage = read('trials/index.html');
  check(trialNames.length > 0, '시련 카드 이름을 코드에서 하나도 못 읽었다.');
  for (const name of trialNames) {
    if (trialPage.includes(name)) trialCount += 1;
    else problems.push(`시련 카드 「${name}」 이 드래곤 시련 페이지에 없다.`);
  }
} catch {
  problems.push('드래곤 시련 페이지가 안 나왔다.');
}

// 5. 랜딩에 정의 파일에서 온 숫자가 찍혀 있다.
const home = read('index.html');
check(home.includes(String(perks.length)), `랜딩에 증강 개수 ${perks.length} 가 없다.`);

// 6. base 가 빠진 사이트 안쪽 링크가 없다.
const PAGES = [
  'index.html',
  'perks/index.html',
  'sets/index.html',
  'trials/index.html',
  'guide/index.html',
  'guide/install/index.html',
  'guide/start/index.html',
  'guide/perks/index.html',
  'guide/sets/index.html',
  'guide/economy/index.html',
  'guide/commands/index.html',
];
for (const path of PAGES) {
  const page = read(path);
  for (const match of page.matchAll(/(?:href|src)="(\/[^"]*)"/g)) {
    const link = match[1];
    check(link.startsWith('/sharedfate/'), `${path} 에 base 가 빠진 링크가 있다 — ${link}`);
  }
}

if (problems.length > 0) {
  console.error('결과물 확인 실패:');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

console.log(`결과물 확인 통과 — 증강 ${cards}장 · 세트 ${sections}유형 · 가이드 6꼭지 · 시련 카드 ${trialCount}장 · 드래곤전 대비 ${trialCards}장`);
