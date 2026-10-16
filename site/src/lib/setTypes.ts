/**
 * 유형 id → 한국어 이름. PerkSetType.java 의 enum 과 같아야 한다.
 * 어긋나면 tests/setTypes.test.ts 가 깨진다.
 */
export const SET_TYPE_NAMES: Record<string, string> = {
  weapon: '무기',
  power: '화력',
  hunt: '사냥',
  mining: '채굴',
  supply: '보급',
  defense: '방어',
  survival: '생존',
  recovery: '회복',
  swap: '교환',
  gamble: '도박',
  mobility: '기동',
  blessing: '가호',
  pioneer: '개척',
  bond: '결속',
};

/** 화면에 늘어놓는 차례. PerkSetType.java 의 선언 순서다. */
export const SET_TYPE_ORDER = Object.keys(SET_TYPE_NAMES);

/** 모르는 id 가 오면 id 를 그대로 보여 준다 — 화면이 비는 것보다 낫다. */
export function setTypeName(id: string): string {
  return SET_TYPE_NAMES[id] ?? id;
}
