package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.TracedProjectile} 실행기 — 기둥에서 날아오는 불덩이.
 *
 * <h2>왜 바닐라 화염구를 띄우지 않는가</h2>
 *
 * <p>{@code LargeFireball} 의 블록 파괴는 {@code mobGriefing} 게임룰에 묶여 있다. 우리만 끌 수
 * 없고, 끄면 크리퍼와 엔더맨까지 함께 꺼진다. 폭발에는 불도 딸려 붙는다. 엔드 섬에 구멍이 하나
 * 뚫리면 <b>다음 전투부터 발판이 달라지고</b>, 그 구멍은 판이 끝날 때까지 남는다. 그래서 날아오는
 * 모습은 파티클로 그리고 착탄은 직접 계산한다 — 보이는 것은 같고 블록은 한 칸도 건드리지 않는다.
 *
 * <h2>궤적이 곧 예고다</h2>
 *
 * <p>다른 카드는 「바닥에 고리가 생기고 잠시 뒤 터진다」지만 이 카드는 <b>날아오는 것이 보인다.</b>
 * 그래서 피하는 법도 다르다 — 고리 밖으로 한 걸음 나가는 것이 아니라 <b>선이 가리키는 자리에서
 * 비키는 것</b>이다. 조준점을 발사 시점에 얼려 두는 이유가 여기 있다. 사람을 계속 쫓아가면 궤적이
 * 아무것도 알려 주지 않는 장식이 되고, 회피가 불가능해진다.
 *
 * <h2>왜 위상 계산을 {@link TrialRisks} 에서 가져오는가</h2>
 *
 * <p>「받은 틱부터 센다」·「받은 직후 한 주기는 온전히 예고에 쓴다」·「복원 직후 음수 위상을 막는다」는
 * 실행기마다 다시 풀 문제가 아니다. 다섯 실행기가 각자 나눗셈을 하면 그중 하나는 반드시 하나씩
 * 어긋나고, 그 하나만 예고 없이 터진다. 같은 패키지이므로 {@code TrialRisks} 의 것을 그대로 부른다.
 *
 * <h2>넉백을 주지 않는다</h2>
 *
 * <p>엔드 중앙 섬은 사방이 허공이다. 밀려서 가장자리를 넘으면 대응할 방법이 없는 즉사이고, 공유
 * 체력이라 한 사람의 낙사가 팀 전체를 끝낸다. 피해를 줄 때 엔티티가 붙지 않은 피해원을 쓰는 것이
 * 그 장치다 — {@code LivingEntity} 는 피해원에 실체가 있을 때만 밀어낸다.
 */
public final class TrialFireball {

	/** 궤적 꼬리 점 사이 간격(블록). 촘촘하게 찍으면 한 발에 파티클 패킷이 수백 개 나간다. */
	private static final double TRAIL_STEP = 1.5;
	/** 꼬리 점 수 상한. 기둥이 멀수록 선이 길어지므로 거리에 비례해 늘어나는 것을 여기서 끊는다. */
	private static final int TRAIL_MAX_POINTS = 24;

	/**
	 * 날아가고 있는 한 발.
	 *
	 * <p>{@code to} 가 좌표인 것이 핵심이다. 사람을 들고 있으면 매 틱 그 사람의 현재 위치를 읽게
	 * 되고, 그 순간 조준이 다시 따라다니기 시작한다.
	 *
	 * @param targetId 이 발이 노린 사람. 자막을 그 사람에게만 띄우려고 들고 있다
	 * @param from     발사점 — 가장 가까운 기둥 꼭대기
	 * @param to       발사 순간 얼린 조준점
	 */
	private record Shot(UUID targetId, Vec3 from, Vec3 to) {
	}

	/** 한 주기에 쏜 것들. 주기 번호가 바뀔 때만 다시 만든다. */
	private record Volley(long index, List<Shot> shots) {
	}

	/**
	 * 지금 날고 있는 것들.
	 *
	 * <p><b>열쇠에 주의.</b> 공개 진입점이 카드 id 를 받지 않으므로 「받은 틱 + 위험 값」으로 열쇠를
	 * 만든다. 실제로는 이 위험을 가진 카드가 하나뿐이라 충분하지만, 언젠가 <b>값이 완전히 같은
	 * 화염구 위험 둘을 한 카드에</b> 걸면 두 발이 한 발로 합쳐진다. 그때는 진입점에 열쇠 인자를
	 * 더해야 한다 — {@code TrialRisks} 가 {@code id + '#' + index} 로 푼 문제와 같은 것이다.
	 */
	private static final Map<Key, Volley> VOLLEYS = new HashMap<>();

	private record Key(long granted, TrialCatalog.Risk.TracedProjectile risk) {
	}

	private TrialFireball() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>{@code dragon} 은 이 카드가 쓰지 않는다. 그래도 받는 것은 다섯 실행기의 진입점을 같은
	 * 모양으로 두어 {@code DragonTrialManager} 의 배선이 갈래 없이 한 줄로 끝나게 하기 위해서다.
	 *
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now,
			@Nullable TrialCatalog.Risk.TracedProjectile risk) {
		if (end == null || members == null || members.isEmpty() || risk == null) {
			return;
		}
		int interval = risk.interval();
		if (interval <= 0 || risk.count() <= 0 || !(risk.radius() > 0.0)) {
			return;
		}
		// 받은 바로 그 틱에는 아직 아무 주기도 시작되지 않았다.
		if (TrialRisks.elapsedSinceGrant(now, granted) <= 0L) {
			return;
		}

		int window = traceWindow(interval, risk.traceTicks());
		int remaining = TrialRisks.remainingTicks(now, granted, interval);
		if (remaining > window) {
			// 아직 쏘지 않았다. 다음 발까지 하늘이 비어 있는 것이 정상이다.
			return;
		}

		Key key = new Key(granted, risk);
		long cycle = TrialRisks.strikeIndex(now, granted, interval);
		Volley volley = VOLLEYS.get(key);
		if (volley == null || volley.index() != cycle) {
			volley = fire(end, members, cycle, risk);
			VOLLEYS.put(key, volley);
		}
		if (volley.shots().isEmpty()) {
			return;
		}

		double progress = flightProgress(remaining, window);
		show(end, members, volley, remaining, progress, risk);

		if (TrialRisks.firesAt(now, granted, interval)) {
			for (Shot shot : volley.shots()) {
				detonate(end, shot.to(), risk);
			}
			// 터진 발을 들고 있으면 다음 주기의 첫 틱에 옛 궤적이 한 번 더 그려진다.
			VOLLEYS.remove(key);
		}
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 옛 판의 좌표가 새 판에서 터지지 않게 한다. */
	public static void clearState() {
		VOLLEYS.clear();
	}

	// ------------------------------------------------------------------ 발사

	/**
	 * 이번 주기에 쏠 것들을 정한다.
	 *
	 * <p>대상은 주기마다 새로 뽑는다 — 한 사람만 계속 노리면 그 사람만 게임을 하게 된다. 다만
	 * 뽑은 뒤에는 그 주기가 끝날 때까지 바꾸지 않는다. 매 틱 다시 뽑으면 궤적이 사람들 사이를
	 * 뛰어다녀 아무도 자기 것인지 알 수 없다.
	 *
	 * <p>기둥이 하나도 없는 월드(지형을 갈아엎은 판)에서는 발사점이 없다. 그때는 그 발을 버린다 —
	 * 어딘가 아무 데서나 쏘면 궤적이 예고 노릇을 못 한다.
	 */
	private static Volley fire(ServerLevel end, List<ServerPlayer> members, long cycle,
			TrialCatalog.Risk.TracedProjectile risk) {
		List<Shot> shots = new ArrayList<>();
		for (int index : TrialRisks.pickIndexes(risk.count(), members.size(), end.getRandom())) {
			ServerPlayer target = members.get(index);
			Vec3 aim = target.position();
			Vec3 muzzle = EndPillars.nearestTop(end, aim);
			if (muzzle == null) {
				continue;
			}
			shots.add(new Shot(target.getUUID(), muzzle, aim));
			end.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.GHAST_SHOOT,
					SoundSource.HOSTILE, 4.0F, 1.0F);
		}
		return new Volley(cycle, shots);
	}

	// ------------------------------------------------------------------ 보여 주기

	/**
	 * 궤적과 경고를 함께 낸다.
	 *
	 * <p>궤적만으로는 <b>어디에 떨어지는지</b>가 흐리다. 선은 방향을 알려 주지만 끝점은 원근 때문에
	 * 읽기 어렵다. 그래서 착탄 지점에 {@link TrialWarning} 의 바닥 고리를 함께 그린다 — 다른
	 * 카드에서 이미 「서 있으면 죽는다」로 배운 그 빨강이라 새로 배울 것이 없다.
	 */
	private static void show(ServerLevel end, List<ServerPlayer> members, Volley volley,
			int remaining, double progress, TrialCatalog.Risk.TracedProjectile risk) {
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		boolean changed = stage != null && TrialRisks.stageJustChanged(remaining);
		for (Shot shot : volley.shots()) {
			drawTrace(end, shot.from(), shot.to(), progress);
			TrialWarning.markGround(end, shot.to(), risk.radius());
			if (changed) {
				TrialWarning.sound(end, shot.to(), stage);
			}
		}
		if (remaining == TrialWarning.TICKS_SIDESTEP) {
			TrialWarning.shout(aimedAt(members, volley), Component.literal("불덩이가 옵니다 — 자리를 비우십시오"));
		}
	}

	/** 이번 주기에 노려진 사람들. 접속을 끊었으면 빠진다. */
	private static List<ServerPlayer> aimedAt(List<ServerPlayer> members, Volley volley) {
		List<ServerPlayer> aimed = new ArrayList<>();
		for (ServerPlayer member : members) {
			for (Shot shot : volley.shots()) {
				if (shot.targetId().equals(member.getUUID())) {
					aimed.add(member);
					break;
				}
			}
		}
		return aimed;
	}

	/**
	 * 날아오는 모습을 그린다.
	 *
	 * <p>선 전체를 매 틱 다 그리면 「불덩이가 온다」가 아니라 「기둥과 내가 빨간 실로 묶였다」가 된다.
	 * <b>지금까지 날아온 만큼</b>만 성긴 연기로 남기고 선두에만 불꽃을 몰아 찍는다. 그러면 밝은
	 * 덩어리 하나가 기둥에서 출발해 다가오는 것으로 읽힌다.
	 */
	private static void drawTrace(ServerLevel end, Vec3 from, Vec3 to, double progress) {
		Vec3 head = headAt(from, to, progress);
		int points = trailSamples(from.distanceTo(head));
		for (int index = 0; index < points; index++) {
			double along = (double) index / points;
			Vec3 point = from.add(head.subtract(from).scale(along));
			end.sendParticles(ParticleTypes.SMOKE, point.x, point.y, point.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
		end.sendParticles(ParticleTypes.FLAME, head.x, head.y, head.z, 8,
				0.25, 0.25, 0.25, 0.01);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, head.x, head.y, head.z, 2,
				0.15, 0.15, 0.15, 0.0);
	}

	// ------------------------------------------------------------------ 착탄

	/**
	 * 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p>피해원에 엔티티를 달지 않는 것이 <b>넉백을 막는 장치</b>다. 실체가 붙은 피해원이면
	 * {@code LivingEntity} 가 스스로 밀어내는데, 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다.
	 * 폭발 피해형을 쓰므로 폭발 보호는 그대로 듣는다 — 대비한 사람이 손해 보지 않아야 한다.
	 */
	private static void detonate(ServerLevel end, Vec3 at, TrialCatalog.Risk.TracedProjectile risk) {
		end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1,
				0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 20,
				risk.radius() * 0.4, 0.3, risk.radius() * 0.4, 0.02);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
				3.0F, 1.0F);
		// 상자로 후보를 추린 뒤 고리로 다시 거른다. inflate 는 정육면체라 표식 밖에서도 걸린다.
		for (ServerPlayer nearby : end.getEntitiesOfClass(ServerPlayer.class,
				new AABB(at, at).inflate(risk.radius()))) {
			if (!TrialRisks.insideMark(nearby.position(), at, risk.radius())) {
				continue;
			}
			nearby.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
		}
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 실제로 쓰는 궤적 길이.
	 *
	 * <p>카드에 적힌 {@code traceTicks} 가 주기보다 길면 <b>다음 발이 앞 발이 아직 날고 있는 동안
	 * 출발</b>한다. 한 자리에 한 발만 들고 있으므로 앞 발은 그대로 사라지고, 이미 그려 놓은 궤적과
	 * 바닥 고리는 아무 일 없이 지워진다 — <b>예고가 거짓말을 한 것</b>이 된다. 월드가 삭제되는
	 * 게임에서 그것은 카드를 잘못 적은 사람의 실수로 끝나지 않는다.
	 *
	 * <p>그래서 주기로 깎는다. 깎이면 앞 발이 터진 바로 다음 틱에 다음 발이 출발해 하늘에 언제나
	 * 정확히 한 발만 있고, <b>그려진 궤적은 반드시 착탄으로 끝난다.</b> 카드를 적은 사람의 의도보다
	 * 예고가 짧아지는 것은 맞지만, 그 경우에도 예고는 주기 전체 길이라 가장 긴 예고다.
	 */
	static int traceWindow(int interval, int traceTicks) {
		if (interval <= 0 || traceTicks <= 0) {
			return 0;
		}
		return Math.min(traceTicks, interval);
	}

	/**
	 * 지금까지 날아온 비율. 0 이 발사점, 1 이 조준점.
	 *
	 * <p>남은 틱으로 세는 이유는 <b>도착이 곧 발동</b>이어야 하기 때문이다. 발사 틱부터 따로 세면
	 * 어딘가에서 하나가 어긋나 착탄 한 틱 전에 터지거나 도착하고도 한 틱 더 날아간다. 남은 틱이 0 인
	 * 틱은 {@link TrialRisks#firesAt} 가 참인 틱과 같은 정의라 어긋날 자리가 없다.
	 *
	 * @param remainingTicks 발동까지 남은 틱
	 * @param window         {@link #traceWindow} 가 돌려준 실제 궤적 길이
	 */
	static double flightProgress(int remainingTicks, int window) {
		if (window <= 0) {
			// 궤적이 없는 카드. 예고 없이 그 자리에서 터지는 셈이므로 늘 도착해 있다.
			return 1.0;
		}
		if (remainingTicks >= window) {
			return 0.0;
		}
		if (remainingTicks <= 0) {
			return 1.0;
		}
		return (double) (window - remainingTicks) / window;
	}

	/**
	 * 그 비율일 때 불덩이가 있는 자리.
	 *
	 * <p>비율을 잘라 두는 것은 안전장치가 아니라 <b>규칙</b>이다. 1 을 넘겨 그리면 불덩이가 조준점을
	 * 지나쳐 날아가고, 그 뒤에 터지면 사람들은 「지나간 자리」에서 폭발을 본다.
	 */
	static Vec3 headAt(Vec3 from, Vec3 to, double progress) {
		double along = Math.max(0.0, Math.min(1.0, progress));
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 꼬리에 찍을 점 수.
	 *
	 * <p>기둥까지의 거리는 월드마다 다르고 아레나 반대편 기둥이면 100 블록이 넘는다. 간격만 정하고
	 * 두면 한 발에 점 70 개가 나가고, 네 명이 각자 한 발씩 받는 카드가 붙는 순간 파티클 패킷만으로
	 * 틱이 밀린다. 상한이 있으면 먼 궤적은 성겨질 뿐 선은 그대로 읽힌다.
	 */
	static int trailSamples(double travelled) {
		if (!(travelled > 0.0)) {
			return 0;
		}
		return Math.min(TRAIL_MAX_POINTS, (int) Math.floor(travelled / TRAIL_STEP));
	}
}
