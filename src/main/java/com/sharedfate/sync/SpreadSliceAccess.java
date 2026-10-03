package com.sharedfate.sync;

/**
 * 「완충」이 미뤄 둔 몫을 넣을 때 {@code LivingEntity.lastHurt} 를 읽고 쓰는 통로다.
 *
 * <p>{@code LivingEntity} 에 {@code LivingEntityPerkDamageMixin} 이 구현을 얹는다. 그 믹스인이
 * 이미 {@code lastHurt} 를 {@code @Shadow} 로 끌어오고 있어 새 믹스인도 등록도 필요 없다.
 *
 * <h2>왜 필요한가</h2>
 * <p>{@code lastHurt} 는 {@code protected} 라 {@link SpreadDamageManager} 가 직접 만질 수 없다.
 * 그런데 몫을 「피격 연출 없이」 넣으려면 바닐라 {@code hurtServer} 의 <b>쿨타임 안 추가 피해</b>
 * 갈래로 보내야 하고, 그 갈래는 {@code amount - lastHurt} 만 넣는다. 그래서 몫을 넣는 동안만
 * {@code lastHurt} 를 0 으로 두었다가 되돌린다. 자세한 까닭은
 * {@code SpreadDamageManager.deliver} 머리말에 있다.
 */
public interface SpreadSliceAccess {
	/** 지금의 {@code lastHurt}. */
	float sharedfate$lastHurt();

	/** {@code lastHurt} 를 갈아 끼운다. */
	void sharedfate$setLastHurt(float value);
}
