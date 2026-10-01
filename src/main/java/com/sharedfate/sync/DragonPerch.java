package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 드래곤 <b>기본 패시브</b> — 포디움에 내려앉은 드래곤의 두 가지 성질.
 *
 * <ol>
 *   <li><b>착지하면 {@value #HOLD_TICKS} 틱 동안 못 일어난다.</b> 사람 말:
 *       <b>「착지후 너무 빨리 일어나던데 착지하고 어느시간동안은 안올라가게 해야할거같아」</b></li>
 *   <li><b>앉아 있는 동안은 근접 피해만 들어간다.</b> 사람 말:
 *       <b>「착지햇을떄는 원거리 공격 안받고 근접공격만 데미지 들어가게 해야해」</b></li>
 * </ol>
 *
 * <p>카드가 아니라 <b>패시브</b>다({@link DragonPassives} 의 「왜 시련과 갈라 두는가」). 룰렛에
 * 없고 저장하지 않으며, 시련을 켠 팀의 전투가 열려 있는 동안 <b>언제나</b> 돈다. 「착지 충격」
 * 카드가 걸렸을 때만 켜지게 하지 않은 까닭은 사람이 배우는 것 때문이다 — 카드마다 앉아 있는
 * 시간이 다르면 「착지하면 {@value #HOLD_TICKS} 틱」을 배울 수 없고, 그때는 외우는 것이 아니라
 * 매번 다시 재는 것이 된다.
 *
 * <h2>⚠⚠ 「최후의 저항」을 거르는 자리가 여기다 — 깃발 둘로 막는다</h2>
 *
 * <p>「최후의 저항」(체력 30% 별개 보스전)은 드래곤을 {@code EnderDragonPhase.HOVERING} 으로
 * <b>영구히 앉혀 두고 사람이 활로 115초에 1200 을 깎는</b> 설계다. 그런데 {@code HOVERING} 은
 * <b>{@code isSitting()} 이 참</b>이다(26.3 {@code DragonHoverPhase.isSitting} 이
 * {@code iconst_1 / ireturn} 두 줄이다). 곧 <b>{@code isSitting()} 으로 「앉았다」를 물으면
 * 최후의 저항이 통째로 거짓이 된다</b> — 활이 한 대도 안 들어가 그 페이즈를 이길 방법이 사라진다.
 *
 * <p>그래서 막는 것을 둘로 겹쳤다. 「한쪽만 막으면 반드시 샌다」가 이 저장소의 규약이다.
 *
 * <ol>
 *   <li><b>구조로 막는다.</b> {@link #perched} 가 {@code isSitting()} 을 묻지 않고
 *       <b>{@code SITTING_SCANNING}·{@code SITTING_ATTACKING}·{@code SITTING_FLAMING} 세
 *       상수와만</b> 견준다. {@code HOVERING} 은 그 셋에 없으므로 <b>이 파일에 그 이름이
 *       등장할 자리가 없다</b> — {@code DragonPerchTest} 가 클래스 파일에 그 글자가 없는 것을
 *       못박는다</li>
 *   <li><b>배선으로 막는다.</b> {@code DragonTrialManager} 가 최후의 저항이 열린 틱에
 *       {@link #standDown()} 을 부른다. 앞으로 누가 {@code HOVERING} 을 앉은 칸으로 세더라도
 *       그 줄이 깃발을 내린다</li>
 * </ol>
 *
 * <h2>왜 이 파일은 페이즈를 <b>읽는가</b> — {@link TrialLandingShock} 은 안 읽는데</h2>
 *
 * <p>그쪽은 좌표 둘로 착지를 알아낸다. 페이즈를 읽지도 않는 것이 「나중에 이 규칙을 어길 실마리
 * 자체를 남기지 않는다」는 선택이었다(그쪽 클래스 설명).
 *
 * <p>여기서는 <b>좌표로는 풀 수 없다.</b> 최후의 저항의 {@code HOVERING} 앵커도 포디움 바로
 * 위이고 매 틱 {@code setDeltaMovement(ZERO)} 로 멈춰 있어서, 「포탈 꼭대기에서 1칸 안 +
 * 안 움직인다」가 <b>그쪽에서도 참</b>이다. 두 상태를 가르는 사실은 페이즈 하나뿐이다.
 *
 * <p>대신 <b>쓰지는 않는다.</b> {@code setPhase}·{@code setTarget} 을 부르지 않고
 * {@code getPhaseManager().getCurrentPhase().getPhase()} 한 줄만 읽는다. 「표적」 카드가
 * 드래곤을 돌진 페이즈로 밀었다가 <b>착지를 아예 안 하게</b> 만든 사고는 <b>쓰는 쪽</b>에서
 * 났다 — 바닐라는 경로가 끝난 틱에만 「착지할까」를 굴리는데 {@code setPhase} 가 부르는
 * {@code begin()} 이 그 경로를 지운다.
 *
 * <h2>이륙을 어떻게 막는가 — 문 하나를 잠근다</h2>
 *
 * <p>26.3 에서 앉은 드래곤이 일어나는 길이 <b>셋</b>이다. 전부 바이트코드에서 세었다.
 *
 * <ol>
 *   <li>{@code DragonSittingScanningPhase.doServerTick} — 반경 20 안에 사람이 없으면
 *       {@code scanningTime >= 100} 인 틱에 {@code TAKEOFF}(사람이 반경 150 안에 있으면 곧바로
 *       {@code CHARGING_PLAYER} 로 덮어쓴다). <b>이 100틱이 사람이 「너무 빨리 일어난다」고 한
 *       바로 그 5초다</b></li>
 *   <li>{@code DragonSittingFlamingPhase.doServerTick} — {@code flameTicks >= 200} 이고
 *       {@code flameCount >= 4} 면 {@code TAKEOFF}</li>
 *   <li>{@code EnderDragon.hurt} — 앉은 동안 받은 누적 피해가
 *       {@code 0.25 × getMaxHealth()}(바이트코드의 {@code SITTING_ALLOWED_DAMAGE_PERCENTAGE})를
 *       넘으면 {@code TAKEOFF}</li>
 * </ol>
 *
 * <p>셋을 따로 막으면 「한쪽만 막으면 반드시 샌다」에 그대로 걸린다. 셋이 <b>전부</b> 지나는
 * 자리가 {@code EnderDragonPhaseManager.setPhase} 하나이므로 거기를 잠근다
 * ({@code EnderDragonPerchHoldMixin}).
 *
 * <p>⚠ 3번 길은 막혀도 {@code sittingDamageReceived} 를 <b>0 으로 되돌리고</b> 나서 막힌다 —
 * 바닐라가 {@code setPhase} 보다 먼저 그 칸을 지운다. 곧 못박아 둔 동안 25% 를 때려 넣어도
 * 일어나지 않고, 고정이 풀린 뒤 다시 25% 를 채워야 한다. 사람에게는 「앉으면 확실히
 * {@value #HOLD_TICKS} 틱은 앉아 있다」로 보이므로 이쪽이 낫다.
 *
 * <h2>원거리를 어떻게 가르는가 — <b>허용 목록</b>이다</h2>
 *
 * <p>사람 말이 「근접공격만 데미지 들어가게」이므로 {@link #melee} 가 <b>통과시킬 것</b>을 센다 —
 * {@code #minecraft:is_player_attack} 하나다. 26.3 의 그 태그를 직접 열어 보면 세 줄
 * ({@code player_attack} · {@code spear} · {@code mace_smash})이고, 사람이 손에 든 것으로
 * 때리는 길은 그 안에 다 들어 있다.
 *
 * <p><b>금지 목록({@code #minecraft:is_projectile})으로 하면 샌다.</b> 26.3 의 그 태그는 여덟
 * 줄이다 — {@code arrow} · {@code trident} · {@code mob_projectile} ·
 * {@code unattributed_fireball} · {@code fireball} · {@code wither_skull} · {@code thrown} ·
 * {@code wind_charge}. 거기에 <b>없는</b> 원거리가 적어도 셋이다.
 *
 * <ul>
 *   <li><b>쇠뇌 폭죽</b> — {@code fireworks} 는 {@code #is_explosion} 쪽이다</li>
 *   <li><b>스플래시 고통 물약</b> — {@code magic} 이다</li>
 *   <li><b>TNT·침대·크리스탈</b> — {@code explosion} 이고, 드래곤에게는 오히려
 *       {@code #always_hurts_ender_dragons}({@code = #is_explosion}) 로 <b>특별히 열려</b> 있다</li>
 * </ul>
 *
 * <p>⚠ <b>{@code EndCrystalGuardMixin} 은 금지 목록을 쓴다.</b> 거기서는 그래야 했다 —
 * 크리스탈을 깨는 길이 근접과 폭발뿐이라 둘을 다 막으면 <b>전투가 끝나지 않는다</b>. 드래곤은
 * 근접이 열려 있으므로 그 제약이 없다. 두 파일이 다른 쪽을 고른 것은 실수가 아니다.
 *
 * <p>⚠ 하나만 예외다 — {@code #minecraft:bypasses_invulnerability}({@code out_of_world} ·
 * {@code generic_kill} 두 줄)는 <b>반드시 통과</b>시킨다. 바닐라가 「무적을 지나친다」고 태그에
 * 적어 둔 것이라, 막으면 {@code /kill} 이 앉은 드래곤에게 안 듣는다.
 *
 * <h2>바닐라와 중복하지 않는가 — 겹치는 자리가 둘 있다</h2>
 *
 * <p>{@code AbstractDragonSittingPhase.onHurt} 는 {@code AbstractArrow} 와 {@code WindCharge} 를
 * <b>이미 0 으로 만들고 그 투사체에 불을 붙인다</b>(26.3 바이트코드에서 확인했다). 앉은 칸 셋이
 * 모두 그 클래스를 물려받고 {@code onHurt} 를 재정의하지 않으므로, <b>화살·삼지창·바람충전은
 * 우리가 없어도 안 들어간다.</b>
 *
 * <p>그래도 우리 쪽에서 함께 막는다. 바닐라 길로 보내면 되먹임이 <b>그 종류만 다르다</b> —
 * 화살은 불이 붙고 폭죽은 아무 일도 안 일어난다. 그러면 사람이 배우는 것이 「원거리는 안
 * 들어간다」가 아니라 「화살은 불타고 폭죽은 버그다」가 된다. <b>잃는 것은 불붙는 화살 하나이고
 * 얻는 것은 모든 원거리가 같은 되먹임을 받는 것</b>이라 이쪽을 골랐다.
 *
 * <h2>막은 것을 사람에게 보인다</h2>
 *
 * <p>아무 반응 없이 0 이 들어가면 사람은 「막혔다」가 아니라 「버그다」로 읽는다. 그래서
 * {@link #deflect} 가 전기 불꽃과 방패 소리를 낸다 — <b>「연결된 수정」({@code TrialCrystalLink}
 * 의 같은 이름 메서드)과 똑같은 한 벌</b>이다. 다른 물건에 같은 일이 일어나므로 같은 신호를
 * 쓰는 것이 맞다. 사람이 배울 것이 「전기 불꽃 + 방패 소리 = 그 한 방은 안 들어갔다」 하나다.
 */
public final class DragonPerch {

	// ------------------------------------------------------------------ 값

	/**
	 * 착지한 뒤 이륙하지 못하는 시간(틱). <b>8초.</b>
	 *
	 * <h2>어디서 나온 숫자인가 — 「착지 충격」의 고리 셋이 섬을 다 지나는 시간이다</h2>
	 *
	 * <p>이 고정이 있어야 하는 까닭은 사람이 <b>패턴을 배울 수 있어야</b> 한다는 것이다. 체력 80%
	 * 의 고정 시련 「착지 충격」이 착지마다 중앙에서 <b>2초 간격으로 고리 셋</b>을 내보내므로,
	 * 적어도 <b>그 셋이 섬을 다 지나갈 때까지</b>는 앉아 있어야 세 번을 읽어 볼 기회가 생긴다.
	 * 드래곤이 그 전에 일어나 버리면 뒤쪽 고리는 「드래곤도 없는데 바닥이 터진다」가 된다.
	 *
	 * <p>산수는 전부 그 카드의 값에서 나온다({@link #holdTicksFor} 가 그 계산이고
	 * {@code DragonPerchTest} 가 카드에서 다시 뽑아 이 상수와 맞춰 본다).
	 *
	 * <ul>
	 *   <li><b>마지막 고리가 떠나는 틱</b> — 간격 40 × (고리 3 − 1) = <b>80</b></li>
	 *   <li><b>고리가 섬 끝까지 가는 시간</b> — 고리는 150틱에 75칸을 가므로 1칸에 2틱이고,
	 *       섬({@code TrialRisks.ARENA_RADIUS} = 40)까지 <b>80</b>틱. 반경 75 는 섬을 한참
	 *       넘으므로 끝까지 기다릴 까닭이 없다 — 그 구간은 허공 위라 사람이 없다</li>
	 * </ul>
	 *
	 * <p>합이 <b>160</b>. 바닐라가 앉아 있던 시간({@code DragonSittingScanningPhase} 의 100틱)의
	 * 1.6배이고, 사람이 반경 20 안에 붙어 있어 바닐라가 스스로 더 앉는 판
	 * ({@code SITTING_ATTACKING} 40 + {@code SITTING_FLAMING} 200)에서는 이 고정이 아무 일도
	 * 하지 않는다 — <b>짧아지는 쪽만 올려 주는 바닥값</b>이다.
	 *
	 * <p>⚠ <b>더 늘리지 말 것.</b> 앉은 드래곤은 머리가 한 칸 내려와 맞기 쉬운 상태이고
	 * ({@code docs/드래곤-트라이얼.md} 4장), 여기를 늘리면 「앉아 있는 동안 공짜로 때린다」가
	 * 전투의 전부가 된다. 바닐라의 25% 이륙({@code EnderDragon.hurt})이 그것을 막으라고 있는
	 * 장치인데 이 고정이 그 장치를 그 시간만큼 끈다.
	 */
	static final int HOLD_TICKS = 160;

	/**
	 * 막았다는 소리를 다시 낼 때까지의 간격(틱).
	 *
	 * <p>10 은 <b>바닐라 피격 무적시간</b>이다. 한 사람이 같은 대상을 때릴 수 있는 가장 짧은
	 * 간격이라, 「막혔다」가 사람 귀에 들리는 횟수의 자연스러운 천장이 그 값이다. 넷이 활을
	 * 쏘면 한 틱에 네 번 막힐 수 있는데 그때마다 울리면 소리가 뭉개져 아무 뜻도 없어진다.
	 */
	private static final int DEFLECT_SOUND_GAP_TICKS = 10;
	/** 한 번 막을 때 튀는 불꽃 수. 한 틱에 한 번만 그리므로 이 값이 곧 틱 예산이다. */
	private static final int DEFLECT_POINTS = 6;
	/** 불꽃을 맞은 자리 주위로 흩는 거리(블록). 드래곤 부위 상자 안에 머무는 값이다. */
	private static final double DEFLECT_SPREAD = 0.6;

	// ------------------------------------------------------------------ 믹스인이 읽는 깃발

	/**
	 * 지금 이륙을 막아야 하는가. <b>{@code EnderDragonPerchHoldMixin} 이 읽는다.</b>
	 *
	 * <p>{@code volatile} 인 까닭은 {@code DragonLastStand.contactDamageOff} 와 같다 — 믹스인은
	 * 남의 클래스 안에서 도는 코드라 어느 스레드가 부를지 이 파일만 보고는 보증할 수 없다.
	 */
	private static volatile boolean held;
	/** 지금 원거리를 막아야 하는가. <b>{@code EnderDragonPerchRangedImmunityMixin} 이 읽는다.</b> */
	private static volatile boolean rangedImmune;

	// ------------------------------------------------------------------ 상태

	/**
	 * 직전 틱에 <b>진짜로</b> 앉아 있었는가. {@code null} 이면 아직 한 번도 판단하지 않았다.
	 *
	 * <p>{@link TrialLandingShock#touchdown} 과 같은 장치다. 「앉아 있다」를 그대로 쓰면 앉은
	 * 내내 참이라 {@link #seatedAt} 이 매 틱 갱신돼 고정이 영영 안 풀린다.
	 */
	private static @Nullable Boolean seated;
	/** 앉은 틱. 고정이 풀리는 시각을 이것으로만 센다. */
	private static long seatedAt = Long.MIN_VALUE;
	/** 마지막으로 막는 소리를 낸 틱. */
	private static long deflectHeardAt = Long.MIN_VALUE;
	/** 마지막으로 막는 불꽃을 그린 틱. 한 틱에 한 번으로 묶는다. */
	private static long deflectDrawnAt = Long.MIN_VALUE;
	/**
	 * 넘겨받은 마지막 {@code now}. <b>믹스인이 시각을 알 수 있는 유일한 길이다.</b>
	 *
	 * <p>{@code level.getGameTime()} 을 쓰면 안 된다 — 시련 화면이 떠 판이 얼어붙은 동안 흐르지
	 * 않아 다른 모든 주기와 어긋난다. 믹스인은 {@code now} 를 받을 자리가 없으므로 이 파일이
	 * 받은 값을 여기 적어 두고 그쪽이 읽는다.
	 */
	private static volatile long nowTick = Long.MIN_VALUE;
	/**
	 * 지금 들고 있는 것이 <b>어느 전투의 기록인가</b>. 전투가 열린 틱으로 가른다.
	 *
	 * <p>{@code TrialLandingShock.beginFight}·{@code DragonFireBarrage.beginFight} 와 같은
	 * 장치다. 배선을 한 줄 빠뜨렸다고 지난 판의 「앉아 있었다」가 남으면 다음 판의 첫 착지가
	 * 가장자리로 안 읽혀 <b>고정이 한 번 건너뛰어진다</b> — 조용한 종류의 사고다.
	 */
	private static long rememberedGrant = Long.MIN_VALUE;

	private DragonPerch() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. 진입점의 모양을 다른 패시브와 맞춘다({@link DragonPassives} 의 「패시브를 하나 더
	 * 붙이려면」).
	 *
	 * <p>⚠ <b>{@link DragonPassives#tick} 의 이른 반환보다 먼저 불려야 한다.</b> 이 파일이 세우는
	 * 것은 믹스인이 읽는 깃발이라, 안 불린 틱에는 <b>직전 값이 그대로 남는다</b> — 드래곤이
	 * 사라졌거나 팀원이 아무도 없는 틱에 깃발이 켜진 채 멈추면 아무도 그것을 내리지 않는다.
	 * 그래서 {@code end}·{@code dragon} 이 비면 여기서 {@link #standDown()} 으로 끝낸다.
	 *
	 * <p>{@code members} 는 쓰지 않는다. 그래도 받는 것은 {@link DragonPassives#tick} 의 호출이
	 * 패시브끼리 한 줄로 유지되게 하기 위해서다 — 되먹임을 보낼 사람은 {@link #deflect} 가
	 * 믹스인에서 받은 월드에서 직접 찾는다(그쪽 설명).
	 *
	 * @param granted 전투가 열린 틱({@link DragonTrialSession#startedTick()})
	 * @param now     지금 게임 시각. {@code level.getGameTime()} 을 쓰지 않는다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now) {
		beginFight(granted);
		nowTick = now;
		if (end == null || dragon == null || !dragon.isAlive() || dragon.isDeadOrDying()) {
			standDown();
			return;
		}
		boolean down = perched(phaseOf(dragon));
		Boolean was = seated;
		seated = down;
		if (!down) {
			seatedAt = Long.MIN_VALUE;
		} else if (TrialLandingShock.touchdown(was, true)) {
			// ⚠ 가장자리에서만 시계를 다시 세운다. 앉은 내내 세우면 고정이 영영 안 풀린다.
			// 「방금 내려앉았는가」를 묻는 함수를 TrialLandingShock 에서 그대로 가져온다 — 같은
			// 뜻의 판별을 두 파일에 적으면 한쪽만 고쳐지고, 그때 어긋나는 것은 숫자가 아니라
			// 「한 번의 착지」가 두 파일에서 다른 것을 가리키게 되는 것이다.
			seatedAt = now;
		}
		// 셋째 갈래는 일부러 없다. was 가 null 이면(= 이미 앉아 있는 드래곤을 처음 본 틱이면)
		// seatedAt 이 MIN_VALUE 로 남아 고정이 걸리지 않고 원거리 면역만 걸린다. 못 본 착지의
		// 시계를 지어내면 사람이 본 것과 어긋난다.
		rangedImmune = down;
		held = down && holding(seatedAt, now);
	}

	/**
	 * 깃발을 내린다. <b>{@code DragonTrialManager} 가 최후의 저항이 열린 틱에 부른다.</b>
	 *
	 * <p>{@link #perched} 가 이미 {@code HOVERING} 을 구조로 거르므로 이 줄이 없어도 지금은
	 * 샐 자리가 없다. 그래도 두는 것은 <b>깃발이 켜진 채 멈추는 길</b>을 막기 위해서다 — 최후의
	 * 저항이 열리면 {@link DragonPassives#tick} 이 아예 안 불리므로, 그 직전 틱에 드래곤이
	 * 앉아 있었다면 켜진 값이 그대로 남는다. 그 상태로 115초를 보내면 <b>활이 한 대도 안
	 * 들어간다.</b>
	 *
	 * <p>앉았던 기억까지 버린다. 최후의 저항이 끝나고 보통 전투로 돌아오는 길은 없지만, 남겨
	 * 두면 그 기억이 다음 판의 첫 착지 판정을 바꾼다.
	 */
	public static void standDown() {
		held = false;
		rangedImmune = false;
		seated = null;
		seatedAt = Long.MIN_VALUE;
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. {@link DragonPassives#clearState()} 가 부른다. */
	public static void clearState() {
		standDown();
		deflectHeardAt = Long.MIN_VALUE;
		deflectDrawnAt = Long.MIN_VALUE;
		nowTick = Long.MIN_VALUE;
		rememberedGrant = Long.MIN_VALUE;
	}

	/** 지난 전투의 찌꺼기를 버린다. 매 틱 불리고 전투가 바뀐 그 틱에만 실제로 지운다. */
	static void beginFight(long granted) {
		if (granted == rememberedGrant) {
			return;
		}
		clearState();
		rememberedGrant = granted;
	}

	// ------------------------------------------------------------------ 판정 (시험이 직접 부른다)

	/**
	 * 이 페이즈가 <b>일반 전투에서 바닐라가 스스로 포디움에 내려앉은 상태</b>인가.
	 *
	 * <p>⚠ <b>{@code isSitting()} 으로 묻지 않는다.</b> 그러면 최후의 저항의
	 * {@code HOVERING} 까지 참이 되어 그 보스전이 통째로 거짓이 된다(클래스 설명). 앉은 칸
	 * 셋만 이름으로 센다 — 26.3 에서 {@code AbstractDragonSittingPhase} 를 물려받는 것이
	 * 정확히 그 셋이고, {@code HOVERING} 은 {@code AbstractDragonPhaseInstance} 를 바로
	 * 물려받는다.
	 */
	static boolean perched(@Nullable EnderDragonPhase<?> phase) {
		return phase == EnderDragonPhase.SITTING_SCANNING
				|| phase == EnderDragonPhase.SITTING_ATTACKING
				|| phase == EnderDragonPhase.SITTING_FLAMING;
	}

	/**
	 * 지금이 고정 구간인가.
	 *
	 * @param seatedAt 앉은 틱. {@link Long#MIN_VALUE} 면 「앉은 틱을 모른다」는 뜻이고 그때는
	 *                 고정하지 않는다 — 못 본 착지의 시계를 지어내지 않는다
	 */
	static boolean holding(long seatedAt, long now) {
		if (seatedAt == Long.MIN_VALUE) {
			return false;
		}
		long age = now - seatedAt;
		return age >= 0L && age < HOLD_TICKS;
	}

	/**
	 * 이 한 방이 <b>통과해야 하는가</b>.
	 *
	 * <p>허용 목록이다(클래스 설명의 「원거리를 어떻게 가르는가」). 사람이 손에 든 것으로 때린
	 * 것과, 바닐라가 「무적을 지나친다」고 태그에 적어 둔 것만 통과한다.
	 *
	 * <p>{@code public} 인 까닭은 믹스인이 다른 패키지에 있기 때문이다. 판별을 믹스인 안에
	 * 복사하면 「원거리란 무엇인가」가 두 곳에 있게 되고, 그때 한쪽만 고쳐진다.
	 */
	public static boolean melee(@Nullable DamageSource source) {
		return source == null
				|| source.is(DamageTypeTags.IS_PLAYER_ATTACK)
				|| source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
	}

	/**
	 * 「착지 충격」 카드의 값에서 {@link #HOLD_TICKS} 를 다시 뽑는다.
	 *
	 * <p>상수를 여기서 계산해 쓰지 않고 <b>시험이 맞춰 보는 쪽</b>으로 둔 까닭은 카드 값이
	 * 런타임에 바뀔 수 있는 것이 아니기 때문이다. 상수로 두면 읽는 사람이 숫자를 바로 보고,
	 * 그 숫자가 어디서 왔는지는 {@code DragonPerchTest} 가 지킨다.
	 *
	 * @param arenaRadius 섬 반경. {@code TrialRisks.ARENA_RADIUS} 다
	 * @return 마지막 고리가 떠나는 틱 + 그 고리가 섬 끝에 닿는 틱
	 */
	static int holdTicksFor(TrialCatalog.Risk.LandingShock risk, double arenaRadius) {
		int lastRingAt = Math.max(0, risk.ringCount() - 1) * Math.max(0, risk.ringIntervalTicks());
		if (!(risk.maxRadius() > 0.0) || risk.travelTicks() <= 0) {
			return lastRingAt;
		}
		double ticksPerBlock = risk.travelTicks() / risk.maxRadius();
		return lastRingAt + (int) Math.ceil(Math.max(0.0, arenaRadius) * ticksPerBlock);
	}

	// ------------------------------------------------------------------ 믹스인이 묻는 것

	/** 지금 이륙을 막아야 하는가. 칸 하나를 읽는 것 이상을 하지 않는다. */
	public static boolean holdsTakeoff() {
		return held;
	}

	/** 지금 원거리를 막아야 하는가. 칸 하나를 읽는 것 이상을 하지 않는다. */
	public static boolean rangedImmune() {
		return rangedImmune;
	}

	/**
	 * 한 방을 튕겨 냈다 — <b>막혔다는 것을 보이고 들린다.</b>
	 *
	 * <p>{@code TrialCrystalLink.deflect} 와 <b>같은 한 벌</b>이다(클래스 설명). 소리는
	 * {@link TrialWarning#playEach} 로 보낸다 — 자리에 놓는 {@code level.playSound} 는 볼륨 1
	 * 이면 16칸에서 끊기고, 사람마다 {@code playSound} 를 부르면 모여 있는 수만큼 겹친다.
	 *
	 * <p>받을 사람을 <b>이 차원에 있는 전원</b>으로 잡는다. 팀 명단을 들고 있으면 되는 일이지만
	 * 그러려면 이 파일이 {@code ServerPlayer} 를 정적으로 쥐어야 하고, 그 참조는 사람이 나간 뒤에도
	 * 남는다. 드래곤은 차원에 하나뿐이라 받는 사람이 거의 같다.
	 *
	 * <p>불꽃은 <b>한 틱에 한 번</b>, 소리는 {@value #DEFLECT_SOUND_GAP_TICKS} 틱에 한 번이다.
	 * 넷이 활을 쏘면 한 틱에 네 번 막힐 수 있는데, 그때마다 내면 점 예산과 소리가 함께 뭉개진다.
	 *
	 * @param at 맞은 부위의 자리. 드래곤 몸이 아니라 <b>맞은 자리</b>여야 「저기서 튕겼다」가 읽힌다
	 */
	public static void deflect(@Nullable ServerLevel level, @Nullable Vec3 at) {
		if (level == null || at == null) {
			return;
		}
		long now = nowTick;
		if (now != deflectDrawnAt) {
			deflectDrawnAt = now;
			for (int index = 0; index < DEFLECT_POINTS; index++) {
				double angle = (Math.PI * 2.0 * index) / DEFLECT_POINTS;
				// 긴 형식으로 보낸다. 짧은 형식은 32칸에서 잘리고 드래곤 머리는 그보다 높다.
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false,
						at.x + Math.cos(angle) * DEFLECT_SPREAD,
						at.y,
						at.z + Math.sin(angle) * DEFLECT_SPREAD,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		// MIN_VALUE 를 뺄셈에 넣지 않는다. 넘치면 「방금 울렸다」가 되어 첫 소리가 사라진다.
		if (deflectHeardAt != Long.MIN_VALUE
				&& now - deflectHeardAt >= 0L && now - deflectHeardAt < DEFLECT_SOUND_GAP_TICKS) {
			return;
		}
		deflectHeardAt = now;
		TrialWarning.playEach(level, level.players(), SoundEvents.SHIELD_BLOCK, 1.0F, 0.8F);
	}

	// ------------------------------------------------------------------ 드래곤에게서 읽는 것

	/**
	 * 지금 페이즈. <b>이 파일이 드래곤에게 묻는 전부</b>다.
	 *
	 * <p>{@code setPhase}·{@code setTarget} 을 부르지 않는다 — 까닭은 클래스 설명의 「왜 이
	 * 파일은 페이즈를 읽는가」에 있다.
	 */
	private static @Nullable EnderDragonPhase<?> phaseOf(EnderDragon dragon) {
		return dragon.getPhaseManager().getCurrentPhase().getPhase();
	}
}
