import { readFileSync } from 'node:fs';
import { PERKS_JSON } from './paths';

/**
 * 증강의 등급. 'trial' 만은 등급이 아니라 「분류」다 — 드래곤 시련을 켠 팀의 45·50 구간에서만
 * 나오는 전용 풀이라 실버·골드·프리즘과 섞이지 않는다(PerkRarity.TRIAL).
 */
export type Rarity = 'silver' | 'gold' | 'prism' | 'trial';

export interface Perk {
  id: string;
  name: string;
  description: string;
  rarity: Rarity;
  /** 유형 id 들. 유형이 없으면 빈 배열이다. 한 증강이 둘을 가질 수 있다. */
  setTypes: string[];
  /** 후보 추첨 가중치. 정의 파일에 없으면 1 이다. 1 보다 작으면 잘 안 나온다. */
  weight: number;
}

interface RawPerk {
  id: string;
  name: string;
  description?: string;
  rarity: Rarity;
  set_types?: string[];
  weight?: number;
}

/** 화면에 늘어놓는 차례. 드래곤전 대비는 등급 셋 뒤에 따로 붙는다. */
export const RARITY_ORDER: Record<Rarity, number> = { silver: 0, gold: 1, prism: 2, trial: 3 };

/** 등급의 한국어 이름. PerkRarity.java 의 displayName 과 같다. */
export const RARITY_NAME: Record<Rarity, string> = {
  silver: '실버',
  gold: '골드',
  prism: '프리즘',
  trial: '드래곤전 대비',
};

/** 정의 파일을 읽어 증강 목록을 낸다. 등급 차례, 같은 등급 안에서는 이름 차례다. */
export function loadPerks(): Perk[] {
  const raw = JSON.parse(readFileSync(PERKS_JSON, 'utf-8')) as { perks: RawPerk[] };
  return raw.perks
    .map((perk) => ({
      id: perk.id,
      name: perk.name,
      description: perk.description ?? '',
      rarity: perk.rarity,
      setTypes: perk.set_types ?? [],
      weight: perk.weight ?? 1,
    }))
    .sort((a, b) => {
      const byRarity = RARITY_ORDER[a.rarity] - RARITY_ORDER[b.rarity];
      return byRarity !== 0 ? byRarity : a.name.localeCompare(b.name, 'ko');
    });
}

/** 등급별 개수. 화면에 「실버 36 · 골드 36 · 프리즘 26 · 드래곤전 대비 12」를 찍을 때 쓴다. */
export function countByRarity(perks: Perk[]): Record<Rarity, number> {
  return {
    silver: perks.filter((p) => p.rarity === 'silver').length,
    gold: perks.filter((p) => p.rarity === 'gold').length,
    prism: perks.filter((p) => p.rarity === 'prism').length,
    trial: perks.filter((p) => p.rarity === 'trial').length,
  };
}
