package com.sharedfate.perk.effect;

/**
 * <b>고른 사람 한 명에게만</b> 걸리는 효과라는 표지.
 *
 * <p>「누가 주인인가」를 {@code TeamState.perkOwners} 에서 직접 찾는 효과들이다.
 * {@link HolderEffect} 로 감쌀 수 없어 저마다 독립 효과 타입이 된 것들이라, 보유자 순환 장치의
 * 바깥에 있다.
 *
 * <h2>왜 표지가 필요한가</h2>
 *
 * <p>주인은 {@code PerkManager.commit} 이 <b>고른 사람이 있을 때만</b> 적는다. 그런데 증강이
 * 들어오는 길은 그것만이 아니다.
 *
 * <ul>
 *   <li>「숨은 재능」·「하늘의 은총」({@code PerkRarityGrant})</li>
 *   <li>「요행」·「도박꾼」({@code PerkGambler})</li>
 *   <li>「환골탈태」({@code PerkRarityReroll}) — <b>주인 기록을 통째로 지우기까지 한다</b></li>
 * </ul>
 *
 * <p>이 길로 들어온 증강은 주인이 없어 {@code perkOwners.get(id)} 가 {@code null} 이고,
 * 그러면 <b>팀의 누구와도 같지 않아 효과가 아무에게도 안 걸린다.</b> 실제로 「비행 부적」과
 * 「열외」가 무작위로 받았을 때 아무 일도 하지 않았다.
 *
 * <p>{@link HolderEffect} 를 가진 증강은 {@code PerkHolderManager.reconcileFixed} 가 주인을
 * 뽑아 굳혀 주지만, 그 장치는 {@code HolderEffect} 가 있어야만 돈다. 이 표지는 <b>그 장치가
 * 닿지 못하는 나머지</b>를 같은 규칙으로 구제하기 위한 것이다.
 *
 * <h2>id 를 하드코딩하지 않는다</h2>
 *
 * <p>새 효과 타입이 「고른 사람만」 규칙을 쓰게 되면 이 인터페이스만 달면 된다. 목록을
 * 어딘가에 적어 두는 방식이면 그때마다 빠뜨린다 — {@link DamageIncreaseReliefEffect} 가 증강
 * id 를 하나도 적지 않고 효과가 실제로 하는 일만 보는 것과 같은 이유다.
 */
public interface OwnerBoundEffect {
}
