package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.DragonFocus} — 드래곤이 한 사람을 표적으로 잡고 구체를 날린다.
 *
 * <h2>드래곤의 행동을 우리가 바꾸지 않는다 — 착지를 멈춘 사고</h2>
 *
 * <p>처음 판은 주기마다 드래곤을 <b>사람에게 돌진하는 페이즈로 밀어넣었다.</b> 실제로 플레이하니
 * <b>드래곤이 착지를 아예 하지 않았다.</b> 까닭은 바닐라 쪽에 있다 —
 * {@code DragonHoldingPatternPhase.findNewTarget} 은 드래곤이 원을 <b>한 바퀴 다 돌아 경로가 끝난
 * 틱</b>에만 {@code nextInt(살아있는크리스탈 + 3) == 0} 으로 「착지할까」를 굴린다. 우리가 중간에
 * 끼어들면 돌아올 때마다 경로가 처음부터 다시 깔려 <b>경로가 끝나는 틱이 영영 오지 않는다.</b>
 * 개입이 차지하는 시간 비율(20%)의 문제가 아니라 <b>주기를 끊은 것</b>이 문제였다. 크리스탈을
 * 먹으러 가는 것도 내려앉아 맞아 주는 것도 사라져 전투가 끝나지 않는 판이 됐다.
 *
 * <p>그래서 <b>페이즈 개입을 전부 걷어냈다.</b> 이 파일은
 * {@code setPhase}·{@code setTarget}·{@code getPhaseManager} 를 <b>한 번도 부르지 않는다.</b>
 * 드래곤에게서 읽는 것은 <b>머리 좌표 하나뿐</b>이고, 그래서 착지도 크리스탈도 바닐라 그대로
 * 돈다. {@code TrialDragonFocusTest} 가 컴파일된 클래스의 상수 풀을 뒤져 그 이름들이 <b>없는지</b>
 * 확인한다 — 이 사고가 다시 들어오면 거기서 걸린다.
 *
 * <h2>그 대신 우리가 직접 날린다</h2>
 *
 * <pre>
 *   0틱      표적을 고른다 — 보라 선 + 표적 자리의 소리 + 발밑 고리
 *   0·40·80·120·160틱   드래곤 머리에서 표적 자리로 구체 한 발씩
 *   200틱    표적 해제
 *   ··· 400틱 쉼 ···
 *   600틱    다시 고른다
 * </pre>
 *
 * <p>발사 간격은 {@code markTicks / shots} 에서 뽑는다({@link #shotInterval}). 따로 숫자를 박아
 * 두면 카드 값을 고칠 때 둘이 어긋나 마지막 발이 표적 해제 뒤에 날아간다.
 *
 * <h2>왜 바닐라 {@code DragonFireball} 을 쓰지 않는가</h2>
 *
 * <p>그 엔티티는 착탄 자리에 브레스 장판({@code AreaEffectCloud})을 남긴다. 우리 카드 어디에도
 * 장판은 적혀 있지 않고, <b>장판을 쓰는 다른 카드가 따로 있다.</b> 둘이 같은 화면에 있으면
 * 무엇이 무엇인지 구별할 수 없다. 그래서 {@link TrialFireball} 이 기둥 화염구에서 이미 푼 방식을
 * 그대로 쓴다 — <b>날아오는 모습은 파티클로 그리고 착탄은 직접 계산한다.</b> 블록은 한 칸도
 * 부수지 않고 불도 붙이지 않는다.
 *
 * <h2>조준은 발사 시점에 얼린다</h2>
 *
 * <p>출발점(드래곤 머리)도 도착점(표적의 발밑)도 <b>쏜 그 틱의 좌표</b>다. 사람을 계속 따라가면
 * 궤적이 아무것도 알려 주지 않는 장식이 되고 회피가 불가능해진다. 이 카드가 요구하는 행동은
 * 「제자리에서 옆으로 비키기」 하나뿐이고, 그래서 비행 시간의 기준도
 * {@link TrialWarning#TICKS_SIDESTEP} 이다.
 *
 * <h2>맞는 사람은 표적 하나뿐이다</h2>
 *
 * <p>착탄 자리에 있는 <b>아무나</b> 때리게 만들면 안 된다. 이 모드는 체력이 팀 공유이고
 * {@code StatMirror.fold} 가 <b>팀원별 피해를 그대로 합산</b>하므로, 넷이 모여 있는 자리에 한
 * 발이 떨어지면 6 × 4 = 24 가 한 틱에 들어가 팀 체력 20 을 넘긴다 — <b>한 발짜리 즉사 카드</b>가
 * 된다. 보라색의 뜻이 「너 하나를 노린다」인 것과도 맞는다. 그래서 착탄 판정은 표적 한 사람에게만
 * 묻는다.
 *
 * <h2>넉백을 주지 않는다</h2>
 *
 * <p>엔드 중앙 섬은 사방이 허공이고, 공유 체력이라 한 사람의 낙사가 팀 전체를 끝낸다. 피해원에
 * 엔티티를 달지 않는 것이 그 장치다 — {@code LivingEntity} 는 피해원에 실체가 있을 때만 밀어낸다.
 *
 * <h2>파티클은 반드시 긴 형태로</h2>
 *
 * <p>드래곤은 y 133 까지 올라가고 아레나를 가로지르면 150 블록이 넘는다. 짧은 형태는 <b>32
 * 블록</b>에서 잘리므로({@link TrialWarning} 의 「거리 제한을 끄고 보낸다」) 되돌리는 순간 구체의
 * 출발 구간 — 곧 「어디서 오는가」 — 가 통째로 사라진다. 이 저장소가 기둥 화염구에서 정확히 그
 * 함정에 빠져 연출의 앞부분을 통째로 잃은 적이 있다.
 */
public final class TrialDragonFocus {

	// ------------------------------------------------------------------ 표적 이름표

	/** 표적 발밑에 그리는 고리의 반경(블록). 사람 하나를 감쌀 만큼만 — 위험 범위가 아니라 이름표다. */
	private static final double MARK_RADIUS = 1.5;

	/**
	 * 표식을 다시 그리는 간격(틱).
	 *
	 * <p>먼지 파티클은 1초 넘게 남으므로 매 틱 찍을 필요가 없다. 표적으로 잡혀 있는 동안 내내
	 * 켜져 있는 표식이라, 매 틱 40점을 뿌리면 이 카드 하나가 파티클 예산을 통째로 먹는다.
	 */
	private static final int MARK_REDRAW_TICKS = 5;

	// ------------------------------------------------------------------ 「저기서 온다」는 선

	/**
	 * 표적을 잡은 뒤 드래곤과 표적을 선으로 잇는 시간(틱).
	 *
	 * <p>표적을 잡은 순간의 문제는 「내가 물렸다」가 아니라 <b>「어디서 날아오는가」</b>다. 드래곤은
	 * 화면 밖 하늘에 있을 수 있어서, 선 한 번이 없으면 첫 구체가 어느 방향에서 오는지 아무도 모른다.
	 * 첫 구체가 도착하기 전까지만 그리면 충분하고, 그 뒤로는 구체 자체가 방향을 말해 준다.
	 */
	static final int BEAM_TICKS = 20;

	/** 선을 다시 긋는 간격(틱). 점 하나가 패킷 한 장이라 매 틱은 과하다. */
	private static final int BEAM_REDRAW_TICKS = 2;

	/** 선 위 점 사이 목표 간격(블록). */
	static final double BEAM_STEP = 2.0;

	/**
	 * 점 사이가 이보다 벌어지면 선이 아니라 점선이다.
	 *
	 * <p>상한에 걸린 뒤로는 점이 늘지 않으므로 거리가 멀수록 간격만 벌어진다. 어디까지 벌어져도
	 * 「저 하늘에서 나에게로 그어진 선」으로 읽히는가가 상한을 정하는 기준이다.
	 */
	static final double BEAM_MAX_GAP = 4.0;

	/**
	 * 이 길이까지는 위 간격을 지킨다.
	 *
	 * <p>드래곤은 y 133 까지 올라가고 광장 바깥 궤도까지 나간다. 반대편 끝에 선 사람까지가
	 * 가로 140·세로 70 이면 156 이므로 그것을 덮는 값이다.
	 */
	static final double BEAM_KEPT_LENGTH = 160.0;

	/**
	 * 선에 찍는 점 수의 상한.
	 *
	 * <p>숫자를 박지 않고 위 둘에서 뽑는다 — 간격이나 약속 길이를 고치면 상한이 따라와야 하는데
	 * 따로 적어 두면 한쪽만 고쳐져 <b>약속이 조용히 깨진다.</b>
	 */
	private static final int BEAM_MAX_POINTS = (int) Math.ceil(BEAM_KEPT_LENGTH / BEAM_MAX_GAP);

	/** 선과 구체가 표적의 발이 아니라 가슴에 닿게 하는 높이. 발밑 고리와 겹치면 둘 다 안 읽힌다. */
	private static final double TARGET_LIFT = 1.0;

	// ------------------------------------------------------------------ 구체

	/**
	 * 구체가 날아가는 시간의 천장(틱).
	 *
	 * <p>이 카드가 요구하는 행동은 <b>「제자리에서 옆으로 비키기」 하나</b>다 — 조준점이 발사
	 * 순간에 얼어붙고 노려지는 사람도 하나이므로 흩어질 필요가 없다. 그 최소 예고가
	 * {@link TrialWarning#TICKS_SIDESTEP} 이고, 여기를 그보다 짧게 잡으면 구체가 예고가 아니라
	 * 사후 통보가 된다. 거리와 무관하게 시간을 고정하는 이유는 <b>드래곤이 멀리 있을수록
	 * 안전해지면 안 되기</b> 때문이다 — 그러면 카드가 드래곤 위치 운으로 갈린다.
	 */
	static final int FLIGHT_MAX_TICKS = TrialWarning.TICKS_SIDESTEP;

	/**
	 * 착탄이 닿는 반경(블록).
	 *
	 * <p>카드 값에 반경이 없다. 이 카드의 반경은 <b>연출이 아니라 규칙</b>이라 카드마다 달라질
	 * 이유가 없기 때문이다 — 「비키면 안 맞는다」가 유일한 대응 수단이고, 그 「비킨다」의 크기가
	 * 여기다. 비행 30틱(1.5초)이면 걸어서 6 블록 넘게 움직이므로 2.5 는 <b>한 걸음 반</b>이다.
	 * 여기를 키우면 다섯 발을 전부 맞는 카드가 되고, 그때 합계는 30 이라 팀 체력 20 을 넘는다.
	 */
	static final double IMPACT_RADIUS = 2.5;

	/** 구체 뒤에 남기는 꼬리 점 수. 선 전체를 매 틱 다시 그리면 「구체」가 아니라 「실」로 읽힌다. */
	private static final int ORB_TAIL_POINTS = 4;

	/** 꼬리 점 사이 거리(블록). */
	private static final double ORB_TAIL_STEP = 1.0;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 노리는 사람과 그 사람을 고른 주기.
	 *
	 * <p>주기 번호를 함께 들고 있어야 「이번 주기에 이미 골랐는가」를 물을 수 있다. 매 틱 다시
	 * 고르면 표식이 사람들 사이를 뛰어다니고 표적 알림 소리가 매 틱 울린다.
	 */
	record Selection(long cycle, UUID target) {
	}

	/**
	 * 이미 쏜 발.
	 *
	 * <p>쏠 틱인지를 위상만으로 판단하면 <b>같은 위상이 두 번 오는 순간 두 발이 나간다.</b>
	 * {@link TrialRisks#elapsedSinceGrant} 가 음수를 0 으로 깎으므로, 월드 시간이 되감긴 판에서는
	 * 위상 0 이 여러 틱 이어진다. 「몇 번째 주기의 몇 번째 발인가」를 들고 있으면 그때도 한 발이다.
	 */
	record Fired(long cycle, int index) {
	}

	/**
	 * 날고 있는 구체 한 발.
	 *
	 * <p>{@code from}·{@code to} 가 엔티티가 아니라 <b>좌표</b>인 것이 핵심이다. 사람이나 드래곤을
	 * 들고 있으면 매 틱 현재 위치를 읽게 되고, 그 순간 구체가 유도탄이 되어 피할 수가 없다.
	 *
	 * @param targetId 이 발이 노린 사람. 착탄 판정을 그 사람에게만 묻는다
	 * @param from     발사 순간의 드래곤 머리
	 * @param to       발사 순간의 표적 발밑
	 * @param firedAt  쏜 틱
	 * @param landsAt  닿는 틱
	 */
	private record Orb(UUID targetId, Vec3 from, Vec3 to, long firedAt, long landsAt) {
	}

	/** 이번 주기의 표적. 위험이 값(레코드)이라 상태를 들 수 없어 여기 둔다. */
	private static @Nullable Selection active;

	/** 마지막으로 쏜 발. 아직 한 발도 안 쐈으면 {@code null}. */
	private static @Nullable Fired lastFired;

	/**
	 * 지금 날고 있는 구체들.
	 *
	 * <p>{@link #flightTicks} 가 발사 간격을 넘지 않으므로 <b>보통 한 발</b>이지만 목록으로 둔다.
	 * 값이 이상하게 적힌 카드에서 두 발이 겹치더라도 앞 발이 조용히 사라지는 것보다 낫다 —
	 * 그려 놓은 궤적은 반드시 착탄으로 끝나야 한다.
	 */
	private static final List<Orb> ORBS = new ArrayList<>();

	private TrialDragonFocus() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>드래곤이 없거나 죽어 있으면 날고 있던 구체까지 버린다 — 죽는 연출이 도는 동안 구체가
	 * 착탄하면 <b>이미 끝난 전투가 사람을 죽인다.</b>
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 주기를 여기서부터 센다
	 * @param now     지금 게임 시각
	 */
	public static void tick(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, String key, long granted, long now,
			TrialCatalog.Risk.DragonFocus risk) {
		if (end == null || risk == null || dragon == null || !dragon.isAlive()
				|| dragon.isDeadOrDying()) {
			ORBS.clear();
			return;
		}

		// 표적이 없어도 이미 날고 있는 것은 끝까지 간다. 쏜 뒤에 표적이 관전으로 넘어갔다고
		// 궤적이 공중에서 사라지면 예고가 거짓말을 한 것이 된다.
		flyOrbs(end, members, risk.damage(), now);

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		int phase = phaseOf(elapsed, risk.markTicks(), risk.restTicks());
		if (!marked(phase, risk.markTicks())) {
			// 쉬는 시간. 다음 주기에는 처음부터 다시 고른다.
			active = null;
			return;
		}

		List<ServerPlayer> targetable = targetable(members);
		if (targetable.isEmpty()) {
			return;
		}
		long cycle = cycleOf(elapsed, risk.markTicks(), risk.restTicks());
		Selection before = active;
		active = select(before, cycle, idsOf(targetable), risk.focus(), CrystalWatch.lastBreaker(),
				end.getRandom());
		if (active == null) {
			return;
		}
		ServerPlayer target = memberOf(targetable, active.target());
		if (target == null) {
			return;
		}

		boolean changed = before == null || !before.target().equals(active.target());
		if (changed) {
			announce(end, target);
		}
		// 바뀐 틱에는 간격을 기다리지 않는다 — 알림과 표식이 따로 오면 누구인지가 흐려진다.
		if (changed || phase % MARK_REDRAW_TICKS == 0) {
			mark(end, target);
		}
		if (phase < BEAM_TICKS && phase % BEAM_REDRAW_TICKS == 0) {
			beam(end, dragon, target);
		}

		int index = shotIndexAt(phase, risk.markTicks(), risk.shots());
		if (index < 0) {
			return;
		}
		Fired shot = new Fired(cycle, index);
		if (!freshShot(lastFired, shot)) {
			return;
		}
		lastFired = shot;
		// 「구체가 날아옵니다」를 띄우던 자리다. 자막을 걷어냈으므로 한 발이 나갔다는 것은
		// fire 의 셜커 발사음(볼륨 4 = 64칸)과 착탄 자리의 보라 고리가 말한다.
		fire(end, dragon, target, now, flightTicks(risk.markTicks(), risk.shots()));
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 옛 표적과 옛 좌표가 다음 판으로 새지 않게 한다. */
	public static void clearState() {
		active = null;
		lastFired = null;
		ORBS.clear();
	}

	// ------------------------------------------------------------------ 구체 날리기

	/**
	 * 한 발 쏜다. 출발점도 도착점도 <b>이 틱의</b> 좌표이고, 쏜 뒤로는 다시 겨누지 않는다.
	 *
	 * <p>소리가 셜커다. 드래곤이 쏘는데 드래곤 소리가 아닌 것은 <b>일부러</b>다 — 장판을 쓰는
	 * 카드가 이미 {@code ENDER_DRAGON_SHOOT} 과 {@code DRAGON_FIREBALL_EXPLODE} 를 쓰고 있어서,
	 * 같은 소리를 쓰면 화면을 안 보고 있을 때 둘을 구별할 수 없다. 색이 갈려 있는 것과 같은
	 * 이유로 소리도 갈라 둔다. 셜커 탄은 엔드의 소리이고 <b>한 사람을 따라오는 보라색 구슬</b>이라
	 * 이 카드의 그림과도 맞는다.
	 */
	private static void fire(ServerLevel end, EnderDragon dragon, ServerPlayer target, long now,
			int flightTicks) {
		if (flightTicks <= 0) {
			return;
		}
		Vec3 muzzle = dragon.head.position();
		ORBS.add(new Orb(target.getUUID(), muzzle, target.position(), now, now + flightTicks));
		end.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.SHULKER_SHOOT,
				SoundSource.HOSTILE, 4.0F, 0.6F);
	}

	/** 날고 있는 것들을 한 칸 옮겨 그리고, 닿은 것을 처리한다. */
	private static void flyOrbs(ServerLevel end, List<ServerPlayer> members, float damage, long now) {
		if (ORBS.isEmpty()) {
			return;
		}
		Iterator<Orb> flying = ORBS.iterator();
		while (flying.hasNext()) {
			Orb orb = flying.next();
			if (now >= orb.landsAt()) {
				land(end, members, orb, damage);
				flying.remove();
				continue;
			}
			drawOrb(end, orb, now);
			// 착탄 자리를 바닥에 그린다. 궤적은 방향을 주지만 끝점은 원근 때문에 안 읽힌다.
			TrialWarning.markGround(end, orb.to(), IMPACT_RADIUS,
					TrialWarning.dust(TrialWarning.Colors.MARKED));
		}
	}

	/**
	 * 닿았다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p>맞는 사람은 <b>표적 하나</b>다. 주변을 긁어모아 때리면 넷이 모여 있는 자리에서 한 발에
	 * {@code 피해 × 4} 가 공유 체력에 들어간다 — 클래스 설명의 「맞는 사람은 표적 하나뿐이다」가
	 * 그 이야기다.
	 *
	 * <p>피해원에 엔티티를 달지 않는 것이 <b>넉백을 막는 장치</b>다. 실체가 붙은 피해원이면
	 * {@code LivingEntity} 가 스스로 밀어내는데, 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다.
	 *
	 * <p>연출용 피해 종류를 {@code dragonBreath} 가 아니라 {@code magic} 으로 고른 것은
	 * <b>사망 메시지</b> 때문이다. 브레스 장판을 쓰는 카드가 따로 있어서 같은 종류를 쓰면 채팅에
	 * 뜨는 한 줄로 둘을 구별할 수 없다. 두 종류 모두 {@code bypasses_armor} 라 방어구 계산은
	 * 어차피 같다.
	 */
	private static void land(ServerLevel end, List<ServerPlayer> members, Orb orb, float damage) {
		Vec3 at = orb.to();
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.MARKED);
		end.sendParticles(dust, true, false, at.x, at.y + TARGET_LIFT, at.z, 24,
				IMPACT_RADIUS * 0.4, 0.4, IMPACT_RADIUS * 0.4, 0.0);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_BULLET_HIT,
				SoundSource.HOSTILE, 2.0F, 0.7F);
		if (damage <= 0.0F) {
			return;
		}
		ServerPlayer target = memberOf(members, orb.targetId());
		if (target == null || !target.isAlive() || target.isSpectator()) {
			return;
		}
		// 높이는 묻지 않는다. 고리가 바닥에 그려지므로 뛰어서 피하는 것은 회피가 아니다.
		if (!TrialRisks.insideMark(target.position(), at, IMPACT_RADIUS)) {
			return;
		}
		target.hurtServer(end, end.damageSources().magic(), damage);
	}

	/**
	 * 날아가는 중인 구체를 그린다.
	 *
	 * <p><b>세 줄 모두 긴 형태다.</b> 첫 {@code boolean} 을 {@code false} 로 되돌리면 구체는 보는
	 * 사람 발밑 32 블록 안에서만 존재한다 — 드래곤은 그 밖에 있으므로 <b>출발 구간이 통째로
	 * 사라지고</b> 구체는 코앞에서 갑자기 나타난다. 그러면 예고가 아니다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 「파티클 줄이기」 설정을 무시할지다. 첫
	 * 깃발이 켜져 있으면 그 검사를 건너뛰므로 값이 무의미하고, 사용자의 설정을 우리가 뒤집을
	 * 이유도 없어 {@code false} 로 둔다.
	 */
	private static void drawOrb(ServerLevel end, Orb orb, long now) {
		Vec3 to = orb.to().add(0.0, TARGET_LIFT, 0.0);
		Vec3 head = orb.from().add(to.subtract(orb.from())
				.scale(flightProgress(now, orb.firedAt(), orb.landsAt())));
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.MARKED);
		end.sendParticles(dust, true, false, head.x, head.y, head.z, 12, 0.35, 0.35, 0.35, 0.0);
		// 꼬리는 「어느 쪽에서 왔는가」만 말하면 되므로 몇 점이면 충분하다.
		Vec3 back = orb.from().subtract(head);
		if (back.lengthSqr() <= 0.0) {
			return;
		}
		Vec3 step = back.normalize().scale(ORB_TAIL_STEP);
		for (int index = 1; index <= ORB_TAIL_POINTS; index++) {
			Vec3 point = head.add(step.scale(index));
			end.sendParticles(dust, true, false, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 알리기

	/**
	 * 표적이 잡혔다고 알린다.
	 *
	 * <p>이 게임은 전멸하면 월드가 지워진다. <b>누가 물렸는지 모르면 대응할 수 없다</b> — 물린
	 * 사람은 떨어져 나가야 하고 나머지는 그 틈에 때려야 한다. 색은
	 * {@link TrialWarning.Colors#MARKED}(「너 하나를 노린다」)이고, 여기서 새 색을 만들지 않는다.
	 *
	 * <p>「드래곤이 당신을 노립니다」를 본인에게 띄우던 줄은 걷어냈다({@link TrialWarning#shout}).
	 * 이제 <b>이 소리와 {@link #mark} 의 보라 고리가 전부</b>다. 소리를 표적 자리에서 내는 것이
	 * 그래서 더 중요해졌다 — 「누구인가」를 말하는 것이 그 자리 하나뿐이다. 한 점에서 울리게
	 * 되돌리면 물린 사람이 누구인지가 사라진다.
	 */
	private static void announce(ServerLevel end, ServerPlayer target) {
		TrialWarning.sound(end, target.position(), TrialWarning.Stage.MARK);
	}

	/** 표적 발밑에 보라색 고리를 그린다. 위험 범위가 아니라 「이 사람이다」라는 이름표다. */
	private static void mark(ServerLevel end, ServerPlayer target) {
		TrialWarning.markGround(end, target.position(), MARK_RADIUS,
				TrialWarning.dust(TrialWarning.Colors.MARKED));
	}

	/**
	 * 드래곤 머리에서 표적까지 보라색 선을 긋는다.
	 *
	 * <p>표적을 잡은 직후에만 그린다. 「내가 물렸다」는 발밑 고리와 표적 자리의 소리가 주지만
	 * <b>「어디서 날아오는가」</b>는 이 선밖에 주지 못한다 — 드래곤은 화면 밖 하늘에 있을 수 있다.
	 *
	 * <p>여기도 <b>긴 형태</b>다. 짧은 형태로 되돌리면 선의 출발점, 곧 드래곤 쪽이 사라져 남는
	 * 것은 발밑의 짧은 토막뿐이다.
	 */
	private static void beam(ServerLevel end, EnderDragon dragon, ServerPlayer target) {
		Vec3 from = dragon.head.position();
		Vec3 to = target.position().add(0.0, TARGET_LIFT, 0.0);
		int points = beamPoints(from.distanceTo(to));
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.MARKED);
		for (int index = 0; index < points; index++) {
			double along = (double) index / points;
			Vec3 point = from.add(to.subtract(from).scale(along));
			end.sendParticles(dust, true, false, point.x, point.y, point.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
		// 출발점에 덩어리를 하나 얹는다. 선만 있으면 어느 쪽 끝이 다가오는 쪽인지 안 읽힌다.
		end.sendParticles(dust, true, false, from.x, from.y, from.z, 8,
				0.6, 0.6, 0.6, 0.0);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 한 주기의 길이(틱). 표적으로 잡혀 있는 시간과 쉬는 시간의 합이다.
	 *
	 * <p>0 을 돌려주지 않는다. 나머지 연산의 밑이라 0 이면 그 자리에서 터진다 — 값이 잘못 적힌
	 * 카드는 <b>심심해질 뿐</b>이어야지 서버를 멈추면 안 된다.
	 */
	static int period(int markTicks, int restTicks) {
		long wanted = (long) Math.max(0, markTicks) + Math.max(0, restTicks);
		return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, wanted));
	}

	/** 지금이 주기의 몇 번째 틱인가. 0 이 표적을 잡는 틱이다. */
	static int phaseOf(long elapsed, int markTicks, int restTicks) {
		return (int) (Math.max(0L, elapsed) % period(markTicks, restTicks));
	}

	/** 몇 번째 주기인가. 0 부터 센다. 표적을 다시 고르는 단위가 이것이다. */
	static long cycleOf(long elapsed, int markTicks, int restTicks) {
		return Math.max(0L, elapsed) / period(markTicks, restTicks);
	}

	/**
	 * 지금 표적을 잡고 있는가.
	 *
	 * <p>{@code markTicks} 가 0 이하인 카드는 <b>영영 거짓</b>이다. 잡는 시간이 없으면 쏠 자리도
	 * 없으므로 카드가 통째로 조용해진다.
	 */
	static boolean marked(int phase, int markTicks) {
		return markTicks > 0 && phase >= 0 && phase < markTicks;
	}

	/**
	 * 발사 간격(틱).
	 *
	 * <p><b>카드 값에서 뽑는다.</b> 따로 숫자를 박아 두면 {@code markTicks} 나 {@code shots} 를
	 * 고칠 때 둘이 어긋나, 마지막 발이 표적 해제 뒤에 날아가거나 다섯 발을 적어 놓고 세 발만
	 * 나가는 카드가 된다.
	 */
	static int shotInterval(int markTicks, int shots) {
		if (markTicks <= 0 || shots <= 0) {
			return 0;
		}
		return Math.max(1, markTicks / shots);
	}

	/**
	 * 이 위상에서 쏘는 발의 번호. 쏘지 않으면 {@code -1}.
	 *
	 * <p>번호로 잘라 내는 것이 규칙이다. {@code markTicks} 가 {@code shots} 로 나누어떨어지지 않는
	 * 카드({@code 10 / 3 = 3})에서는 간격만 보면 위상 0·3·6·9 에 <b>네 발</b>이 나간다 — 카드에
	 * 적힌 수보다 많이 쏘는 것은 값을 적은 사람이 예상할 수 없는 피해다.
	 */
	static int shotIndexAt(int phase, int markTicks, int shots) {
		int interval = shotInterval(markTicks, shots);
		if (interval <= 0 || !marked(phase, markTicks) || phase % interval != 0) {
			return -1;
		}
		int index = phase / interval;
		return index < shots ? index : -1;
	}

	/**
	 * 구체 한 발이 날아가는 시간(틱).
	 *
	 * <p>발사 간격을 넘기지 않는다. 넘기면 <b>앞 발이 아직 날고 있는데 다음 발이 출발</b>해 한
	 * 틱에 두 발이 닿을 수 있고, 그러면 카드 값 하나가 두 배로 들어간다.
	 */
	static int flightTicks(int markTicks, int shots) {
		int interval = shotInterval(markTicks, shots);
		if (interval <= 0) {
			return 0;
		}
		return Math.max(1, Math.min(FLIGHT_MAX_TICKS, interval));
	}

	/**
	 * 지금까지 날아온 비율. 0 이 발사점, 1 이 조준점.
	 *
	 * <p>1 을 넘겨 그리면 구체가 조준점을 지나쳐 날아가고, 사람들은 「지나간 자리」에서 착탄을
	 * 본다. 자르는 것은 안전장치가 아니라 규칙이다.
	 */
	static double flightProgress(long now, long firedAt, long landsAt) {
		long span = landsAt - firedAt;
		if (span <= 0L) {
			return 1.0;
		}
		double along = (double) (now - firedAt) / span;
		return Math.max(0.0, Math.min(1.0, along));
	}

	/**
	 * 아직 안 쏜 발인가.
	 *
	 * <p>{@link Fired} 설명에 적은 「같은 위상이 두 번 오는 판」을 막는 자리다.
	 */
	static boolean freshShot(@Nullable Fired last, Fired now) {
		return last == null || !last.equals(now);
	}

	/** 그 길이의 선에 찍을 점 수. 상한에 걸리면 성겨질 뿐 선은 남는다. */
	static int beamPoints(double length) {
		if (!(length > 0.0)) {
			return 0;
		}
		return Math.min(BEAM_MAX_POINTS, (int) Math.floor(length / BEAM_STEP));
	}

	/**
	 * 그 길이에서 실제로 벌어지는 점 사이 거리(블록).
	 *
	 * <p>점 수만 보면 상한에 걸린 먼 거리가 촘촘한 줄 알게 된다. 선이 <b>선으로 읽히는가</b>를
	 * 숫자로 물을 수 있는 유일한 값이다.
	 */
	static double beamGap(double length) {
		if (!(length > 0.0)) {
			return 0.0;
		}
		int points = beamPoints(length);
		if (points <= 0) {
			return length;
		}
		return length / points;
	}

	/**
	 * 이번 틱의 표적. 주기가 그대로면 들고 있던 사람을 그대로 돌려준다.
	 *
	 * <p>주기 중간이라도 들고 있던 사람이 명단에 없으면 다시 고른다. 접속을 끊었거나 관전으로
	 * 넘어간 사람을 계속 노리면 <b>아무도 안 노리는 것</b>과 같고, 그러면 카드가 죽는다.
	 *
	 * @param held    직전까지 들고 있던 표적. 처음이면 {@code null}
	 * @param members 지금 노릴 수 있는 사람들. 비어 있으면 {@code null} 을 돌려준다
	 * @param breaker 크리스탈을 가장 최근에 깬 사람. 없으면 {@code null}
	 */
	static @Nullable Selection select(@Nullable Selection held, long cycle,
			@Nullable List<UUID> members, TrialCatalog.Risk.Focus focus, @Nullable UUID breaker,
			RandomSource random) {
		if (members == null || members.isEmpty()) {
			return null;
		}
		if (held != null && held.cycle() == cycle && members.contains(held.target())) {
			return held;
		}
		return new Selection(cycle, choose(members, focus, breaker, random));
	}

	/**
	 * 한 사람을 고른다.
	 *
	 * <p>{@code default} 를 넣지 말 것. {@link TrialCatalog.Risk.Focus} 에 갈래를 더하고 여기에
	 * 고르는 법을 안 붙이면 빌드가 깨져야 한다. {@code default} 가 있으면 새 갈래가 조용히
	 * 무작위가 되어, 카드 설명과 실제가 다른 채로 판이 굴러간다.
	 *
	 * <p>깬 사람이 없거나 지금 명단에 없으면 <b>무작위로 물러난다.</b> 아무도 안 노리면 카드가
	 * 죽는다 — 룰렛이 이 카드를 뽑은 판은 시련 없는 판이 되어 버린다.
	 */
	static UUID choose(List<UUID> members, @Nullable TrialCatalog.Risk.Focus focus,
			@Nullable UUID breaker, RandomSource random) {
		if (focus == null) {
			return randomOf(members, random);
		}
		return switch (focus) {
			case CRYSTAL_BREAKER -> breaker != null && members.contains(breaker)
					? breaker
					: randomOf(members, random);
			case RANDOM -> randomOf(members, random);
		};
	}

	private static UUID randomOf(List<UUID> members, RandomSource random) {
		return members.get(random.nextInt(members.size()));
	}

	// ------------------------------------------------------------------ 명단

	/**
	 * 지금 노릴 수 있는 사람들.
	 *
	 * <p>죽어 있거나 관전 중인 사람을 노리면 구체가 시체로 날아간다. 그 사이 살아 있는 사람은
	 * 아무 위협도 받지 않으므로 카드가 꺼진 것과 같다.
	 */
	private static List<ServerPlayer> targetable(@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = new ArrayList<>();
		if (members == null) {
			return alive;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()) {
				alive.add(member);
			}
		}
		return alive;
	}

	private static List<UUID> idsOf(List<ServerPlayer> members) {
		List<UUID> ids = new ArrayList<>(members.size());
		for (ServerPlayer member : members) {
			ids.add(member.getUUID());
		}
		return ids;
	}

	private static @Nullable ServerPlayer memberOf(@Nullable List<ServerPlayer> members, UUID id) {
		if (members == null) {
			return null;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.getUUID().equals(id)) {
				return member;
			}
		}
		return null;
	}
}
