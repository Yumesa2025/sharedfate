import { describe, expect, it } from 'vitest';
import { loadSets } from '../src/lib/sets';
import { SET_TYPE_ORDER } from '../src/lib/setTypes';

const sets = loadSets();

describe('세트 읽기', () => {
  it('PerkSetType 의 선언 순서대로 나온다', () => {
    expect(sets.map((s) => s.type)).toEqual(SET_TYPE_ORDER);
  });

  it('유형마다 한국어 이름이 붙는다', () => {
    const mining = sets.find((s) => s.type === 'mining');
    expect(mining?.name).toBe('채굴');
  });

  it('opensAt 은 단계 가운데 가장 작은 count 다', () => {
    for (const set of sets) {
      expect(set.opensAt).toBe(Math.min(...set.tiers.map((t) => t.count)));
    }
  });

  it('단계는 count 오름차순이고 설명이 비어 있지 않다', () => {
    for (const set of sets) {
      const counts = set.tiers.map((t) => t.count);
      expect(counts).toEqual([...counts].sort((a, b) => a - b));
      for (const tier of set.tiers) {
        expect(tier.description.length).toBeGreaterThan(0);
      }
    }
  });

  it('채굴은 2개부터 열리고 단계가 2·3·4 다', () => {
    const mining = sets.find((s) => s.type === 'mining');
    expect(mining?.opensAt).toBe(2);
    expect(mining?.tiers.map((t) => t.count)).toEqual([2, 3, 4]);
  });

  it('기동은 3개부터 열린다 — 단계가 3 하나뿐이다', () => {
    const mobility = sets.find((s) => s.type === 'mobility');
    expect(mobility?.opensAt).toBe(3);
  });
});

it('방패는 한 개부터 열리고 네 단계가 모두 나온다', () => {
  const shield = sets.find((set) => set.type === 'shield');
  expect(shield?.name).toBe('방패');
  expect(shield?.opensAt).toBe(1);
  expect(shield?.tiers.map((tier) => tier.count)).toEqual([1, 2, 3, 4]);
});
