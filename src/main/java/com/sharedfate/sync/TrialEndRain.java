package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TrialCatalog.Risk.EndRain} 실행기 — 종말의 비.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p><b>한 번만</b> 터진다. 30초({@code durationTicks}) 동안
 * 2~3초({@code minInterval}~{@code maxInterval})마다 30~45곳({@code minSpots}~{@code maxSpots})에
 * 표시가 뜨고, 1.5초({@code warnTicks}) 뒤에 착탄한다. 반경 2.5({@code radius}) ·
 * 피해 10({@code damage}).
 *
 * <p>지점 수는 <b>10~15 였다.</b> 사람이 플레이해 보고 「생성이 지금의 3배여도 괜찮을 것
 * 같다」고 해서 세 배가 됐다. 피해도 반경도 그대로이므로 <b>한 사람이 한 볼리에 한 발</b>은
 * 변하지 않는다({@link TrialRisks#reserveSpots} 가 고리끼리 떼어 놓는다) — 늘어난 것은
 * 「비킬 곳을 고르기」의 어려움이지 한 방의 크기가 아니다.
 *
 * <h2>겹침 금지 규칙을 반드시 태울 것</h2>
 *
 * <p>지점을 직접 굴리지 말고 {@link TrialRisks} 의 <b>살아 있는 지점 목록</b>을 지나게 하라. 한
 * 볼리 안에서만 떨어뜨려 놓는 것으로는 모자란다 — 이 카드와 「낙뢰」(피해 18)는 자리가 달라도
 * <b>둘 다 쌓여 동시에 돈다.</b> 두 고리가 겹친 자리에 선 사람은 한 틱에 28 을 받고, 팀 공유
 * 체력은 20 이다. 전멸은 곧 월드 삭제다.
 *
 * <h2>지점은 {@link TrialRisks#reserveSpots} 로만 잡고 터진 그 틱에 돌려준다</h2>
 *
 * <p>이 파일은 좌표를 스스로 굴리지 않는다. {@code reserveSpots} 는 <b>살아 있는 모든 지점</b>
 * (다른 카드의 것 포함)에서 두 반경의 합보다 멀리 떨어진 자리만 내주므로, 그 길을 지나는 것이
 * 「낙뢰 18 + 종말의 비 10 = 28」을 막는 유일한 장치다.
 *
 * <p>그리고 <b>고리가 터진 그 틱에 {@link TrialRisks#releaseSpots} 로 놓는다.</b> 이 카드의
 * 고리는 예고 30틱 동안만 바닥에 있고 착탄과 함께 사라지므로, 그 뒤로도 자리를 붙들고 있으면
 * 이미 아무것도 없는 곳을 다른 카드가 영영 못 쓰게 된다. {@code LIVE_SPOTS} 는 「지금 바닥에
 * <b>살아 있는</b> 지점」의 목록이지 「이 카드가 쓴 적 있는 자리」의 목록이 아니다.
 *
 * <p>⚠ <b>열쇠는 {@code TrialRisks} 가 쓰는 것과 글자 하나까지 같아야 한다.</b> 분배기가 매 틱
 * {@code LIVE_SPOTS.keySet().retainAll(살아 있는 카드의 열쇠)} 로 없어진 카드의 자리를 놓는데,
 * 다른 모양의 열쇠를 쓰면 <b>잡자마자 매 틱 지워진다</b> — 우리는 남의 고리를 피하는데 남은 우리
 * 고리를 못 보는, 한쪽만 새는 상태가 된다.
 *
 * <p>그래서 <b>열쇠를 만들지 않고 분배기에게 받는다</b>({@link #tick} 의 {@code key}). 한때는
 * 진입점이 열쇠를 안 받아 {@code TrialCatalog.all()} 에서 값으로 되찾는 우회로를 두었는데,
 * 그러면 값이 완전히 같은 위험을 카드 둘에 걸었을 때 앞 카드의 열쇠가 나와 뒤 카드가 겹침
 * 검사에서 빠진다. <b>열쇠를 두 곳에서 만들면 언젠가 갈라진다</b> — 되돌리지 말 것.
 *
 * <h2>예고 30틱은 「제자리에서 옆으로 비키기」의 하한이다</h2>
 *
 * <p>{@link TrialWarning#TICKS_SIDESTEP} 이 정확히 30틱이고 이 카드의 {@code warnTicks} 가 그
 * 값이다. 이 카드가 요구하는 행동이 딱 그것이기 때문이다 — <b>자리는 볼리가 열리는 순간
 * 얼어붙고</b>({@code reserveSpots} 가 내준 좌표를 착탄까지 그대로 들고 간다) 사람을 따라오지
 * 않으므로, 사람은 갈 곳을 고를 것도 넷이 합의할 것도 없이 고리 밖으로 한 걸음 나가기만 하면
 * 된다. 여기를 30 아래로 내리면 예고가 아니라 <b>사후 통보</b>다.
 *
 * <h2>블록을 부수지 않는다 · 넉백을 주지 않는다</h2>
 *
 * <p>바닐라 폭발 엔티티를 띄우지 않고 착탄을 직접 계산한다. 엔드 섬에 구멍이 하나 뚫리면 다음
 * 전투부터 발판이 달라지고, 그 구멍은 이 전투가 유일하게 금지한 「대응 불가 즉사」(공허 낙사)로
 * 이어진다 — {@link TrialFireball} 과 {@link DragonFireBarrage} 가 이미 푼 문제라 같은 답을 쓴다.
 *
 * <p>피해원에 실체를 달지 않는 것이 <b>넉백을 막는 장치</b>다. {@code LivingEntity} 는 피해원에
 * 엔티티가 있을 때만 밀어내는데, 엔드 섬 가장자리에서 밀리면 허공이고 공유 체력이라 한 사람의
 * 낙사가 팀 전체를 끝낸다.
 *
 * <h2>고리 하나는 팀에 한 번만</h2>
 *
 * <p>공유 체력에서 범위 피해는 <b>팀원별로 합산</b>된다. 고리 하나에 넷이 서 있을 때 넷을 다
 * 때리면 40 이고 팀 체력은 20 이다 — 넷이 함께 움직이는 것이 이 게임의 올바른 대응인데 그러면
 * <b>올바른 대응이 전멸</b>이 된다. 「연쇄 포격」이 같은 이유로 원 하나에 한 번만 넣는다
 * ({@link DragonFireBarrage} 의 「팀에게 한 번만」).
 */
public final class TrialEndRain {

	/**
	 * 이 카드가 한 틱에 바닥 표식으로 쓸 수 있는 점 수.
	 *
	 * <p>400 은 이 저장소가 쓰는 한 틱 예산이다 — 「낙뢰」가 반경 3 짜리 고리 열 개로 쓰는 값이고
	 * ({@code TrialEnderPulse.MAX_POINTS_PER_TICK} · {@code TrialEnderStorm.MAX_POINTS_PER_TICK}
	 * 도 같은 400 이다), 그 값을 여기서도 그대로 쓴다.
	 *
	 * <p>이 값은 <b>이 카드 혼자</b>의 몫이다. 시련은 전투가 끝날 때까지 쌓이므로 같은 틱에 남의
	 * 고리도 함께 그려진다는 것을 잊지 말 것.
	 */
	static final int MARK_BUDGET = 400;
	/**
	 * 고리 한 바퀴를 나눠 그릴 수 있는 <b>최대 틱 수</b>.
	 *
	 * <p>{@code TrialWarning.dust} 가 만드는 먼지 파티클의 수명이 <b>최소 8틱</b>이다(26.3
	 * {@code DustParticleBase} 의 생성자 —
	 * {@code max(1, (int)(8.0 / (nextDouble() * 0.8 + 0.2)) * scale)}, 우리는 {@code scale} 이
	 * 1.0 이다). 한 바퀴를 8틱 이상에 걸쳐 그리면 마지막 점을 찍기 전에 첫 점이 죽어
	 * <b>고리가 영영 안 닫힌다.</b> 6 은 그 8 에서 두 틱을 뺀 자리다.
	 */
	static final int MARK_MAX_STRIDE = 6;

	/**
	 * 지금 내리고 있는 비. 열쇠는 분배기가 넘겨주는 위험 열쇠({@code 카드 id + '#' + 순번})다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 남은
	 * 좌표가 새 판에서 터지므로 {@link #clearState()} 로 반드시 비운다.
	 *
	 * <p>칸 하나가 아니라 맵인 것은 <b>「한 번만 터진다」를 기억하는 자리가 여기</b>이기
	 * 때문이다. 자세한 것은 {@link #tick} 에 적어 두었다.
	 */
	private static final Map<String, Downpour> RAINS = new HashMap<>();

	/**
	 * 바닥에 떠 있는 한 볼리.
	 *
	 * <p>{@code spots} 가 좌표인 것이 핵심이다. 사람을 들고 있으면 매 틱 그 사람의 현재 자리를
	 * 읽게 되고, 그 순간 표식이 사람을 쫓아다녀 <b>비킬 수 없는 카드</b>가 된다.
	 *
	 * @param spots   이번 볼리의 고리 중심들. {@link TrialRisks#reserveSpots} 가 내준 자리뿐이다
	 * @param landsAt 착탄하는 틱. 볼리가 열린 틱 + {@code warnTicks} 다
	 */
	private record Volley(List<Vec3> spots, long landsAt) {

		Volley {
			spots = List.copyOf(spots);
		}
	}

	/**
	 * 한 번뿐인 비 전체.
	 *
	 * @param granted      카드를 받은 틱. 이 값이 바뀌면 다른 판에서 받은 카드다
	 * @param nextVolleyAt 다음 볼리를 여는 틱. 볼리마다 새로 굴린다
	 * @param volley       지금 바닥에 떠 있는 볼리. 착탄과 예고 사이가 아니면 {@code null}
	 */
	private record Downpour(long granted, long nextVolleyAt, @Nullable Volley volley) {

		Downpour without() {
			return new Downpour(granted, nextVolleyAt, null);
		}
	}

	private TrialEndRain() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <h2>「한 번만 터진다」를 무엇으로 기억하는가</h2>
	 *
	 * <p>따로 센 횟수를 들지 않는다. <b>받은 틱과 지금 틱의 차이</b>가 전부다 —
	 * {@code now - granted} 가 {@code durationTicks} 를 넘으면 그 뒤로는 영영 아무 일도 하지
	 * 않는다. 그렇게 둔 이유가 둘이다.
	 *
	 * <ul>
	 *   <li><b>되감기와 복원에 강하다.</b> 세션은 저장 파일에서 오고 게임 시각은 월드에서 오므로
	 *       서버를 껐다 켜면 위상이 튄다. 횟수를 세어 두면 그때 한 번 더 쏟아질 수 있지만, 받은
	 *       틱으로 재면 <b>몇 번을 물어도 같은 답</b>이다</li>
	 *   <li><b>비울 것이 없다.</b> 끝났다는 사실이 값에서 나오므로 누가 깃발을 내려 주기를
	 *       기다리지 않는다 — 배선을 한 줄 빠뜨려 「어느 판에서만 두 번 온다」가 되는 길이 없다</li>
	 * </ul>
	 *
	 * <p>{@link #RAINS} 의 칸은 「끝났는가」가 아니라 <b>「아직 돌려주지 않은 자리가 있는가」</b>를
	 * 들고 있다. 그래서 끝나는 틱에 정확히 한 번 {@link TrialRisks#releaseSpots} 가 불린다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 비가 내리는 시간은 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.EndRain risk) {
		if (end == null || members == null || members.isEmpty() || risk == null || !usable(risk)
				|| key == null) {
			return;
		}

		Downpour run = RAINS.get(key);
		if (run != null && run.granted() != granted) {
			// 다른 판에서 받은 카드의 찌꺼기다. 자리를 돌려주고 처음부터 센다.
			finish(key);
			run = null;
		}

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		boolean raining = raining(elapsed, risk);
		if (run == null) {
			if (!raining) {
				// 받은 바로 그 틱이거나, 30초가 이미 끝났다. 끝난 쪽은 영영 여기서 되돌아간다.
				return;
			}
			// 받자마자 떨어뜨리지 않는다. 카드를 받은 틱을 0번째 볼리로 보고 거기서 한 간격을
			// 굴려 첫 볼리를 연다 — 주기를 now % interval 이 아니라 granted 에서 재는 것과 같은
			// 이유다. 카드마다 위상이 저절로 어긋나고, 설명을 읽는 중에 맞지 않는다.
			run = new Downpour(granted, now + rolledInterval(end.getRandom(), risk), null);
		}

		// 순서가 셋이다 — 터뜨리고, 새 볼리를 열고, 떠 있는 것을 그린다. 터뜨리는 것이 먼저라야
		// 방금 터진 고리가 그 틱에 사라지고, 그리는 것이 마지막이라야 이번 틱에 열린 볼리가
		// 첫 틱부터 고리와 소리를 낸다.
		run = land(end, members, key, run, now, risk);
		if (raining) {
			run = openVolley(end, key, run, now, elapsed, risk);
		}
		warn(end, members, run, now, risk);

		if (run.volley() == null && !raining) {
			// 마지막 볼리까지 끝났다. 자리를 돌려주고 기록을 지운다 — 다음 틱부터는 위의
			// 「끝난 쪽은 영영 되돌아간다」로 빠진다.
			finish(key);
			return;
		}
		RAINS.put(key, run);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>잡아 둔 지점을 {@link TrialRisks} 의 살아 있는 목록에서도 놓을 것.</b> 남겨 두면 다음
	 * 판의 다른 카드가 아레나 일부를 영영 쓰지 못한다 — 컴파일도 시험도 조용한 종류의 사고다.
	 *
	 * <p>{@code TrialRisks.clearState()} 는 제 목록을 먼저 비우고 여기를 부르므로 실제로는 지울
	 * 것이 없을 때가 많다. 그래도 놓는 것은 <b>부르는 순서에 기대지 않기 위해서</b>다 — 순서가
	 * 바뀌면 조용히 새는 쪽이 이 함수다.
	 */
	public static void clearState() {
		for (String key : RAINS.keySet()) {
			TrialRisks.releaseSpots(key);
		}
		RAINS.clear();
	}

	// ------------------------------------------------------------------ 볼리 한 번

	/**
	 * 착탄할 때가 됐으면 터뜨리고 자리를 돌려준다.
	 *
	 * <p>착탄하는 틱에는 고리를 다시 그리지 않는다 — 볼리를 여기서 비우므로 {@link #warn} 이
	 * 그릴 것이 없다. 같은 틱에 표식을 한 벌 더 보내면 방금 터진 고리가 한 틱 더 살아 있는
	 * 것으로 보이고, 「터진 자리는 즉시 안전」이 그 한 틱에서 먼저 깨진다 — 「연쇄 포격」이 같은
	 * 자리에서 같은 판단을 한다.
	 */
	private static Downpour land(ServerLevel end, List<ServerPlayer> members, String key,
			Downpour run, long now, TrialCatalog.Risk.EndRain risk) {
		Volley volley = run.volley();
		if (volley == null || now < volley.landsAt()) {
			return run;
		}
		for (Vec3 spot : volley.spots()) {
			detonate(end, members, spot, risk);
		}
		// 고리는 터진 그 틱에 사라진다. 살아 있지 않은 자리를 붙들고 있으면 다른 카드가
		// 아레나의 그만큼을 영영 못 쓴다.
		TrialRisks.releaseSpots(key);
		return run.without();
	}

	/**
	 * 새 볼리를 연다.
	 *
	 * <p>착탄이 30초 안에 들어오는 볼리만 연다. 예고가 끝나기 전에 카드가 끝나면 <b>그려 놓은
	 * 표식이 착탄 없이 사라지고</b>, 그것은 예고가 거짓말을 한 것이 된다 — 이 전투는 이미 보여 준
	 * 표식을 무르지 않는다.
	 *
	 * <p>{@link TrialRisks#reserveSpots} 가 <b>적힌 것보다 적게 내줄 수 있다.</b> 살아 있는 다른
	 * 고리들 때문에 자리를 못 찾은 것이고, 그건 정상이다 — 겹치느니 한 발 빠지는 쪽이다. 한 자리도
	 * 못 얻으면 이번 볼리는 통째로 건너뛰고 <b>다음 볼리 시각은 그대로 굴린다</b>. 매 틱 다시
	 * 시도하면 아레나가 붐빌 때 예고 없이 뜬금없는 틱에 열린다.
	 *
	 * <p>지점이 3배가 되면서 「불러도 안 나오는」 일이 늘어날 자리라 재 봤다. 반경 40 아레나에
	 * 반경 2.5 짜리 45곳은 넓이로 17.6% 라 아직 널널하다 — 20만 판을 굴려 <b>평균 44.94곳</b>이
	 * 나왔고(45곳이 94%, 최소 42곳), 「낙뢰」의 고리 열 개가 이미 서 있어도 <b>44.65곳</b>이다.
	 * 사람이 정한 3배가 실제로도 3배로 일어난다.
	 *
	 * <p>⚠ 대신 <b>반대쪽이 조금 얇아진다.</b> 우리 45곳이 떠 있는 동안 「낙뢰」가 열 곳을 부르면
	 * 평균 9.44곳만 받는다(열 곳이 다 서는 판이 55%). 겹치느니 빠지는 것이 이 저장소의 규칙이라
	 * 그대로 두지만, 낙뢰가 드물게 한두 발 빠지는 것은 이 카드가 만든 일이다.
	 */
	private static Downpour openVolley(ServerLevel end, String key, Downpour run, long now,
			long elapsed, TrialCatalog.Risk.EndRain risk) {
		if (run.volley() != null || now < run.nextVolleyAt() || !landsInWindow(elapsed, risk)) {
			return run;
		}
		RandomSource random = end.getRandom();
		List<Vec3> spots = TrialRisks.reserveSpots(end, key, rolledSpots(random, risk),
				risk.radius());
		long next = now + rolledInterval(random, risk);
		if (spots.isEmpty()) {
			// 예약이 빈 자리를 남겨 두므로 열쇠를 지워 둔다. 「우리가 붙들고 있는 것이 없다」를
			// 목록에도 그대로 적어 두는 쪽이 다음 사람에게 읽기 쉽다.
			TrialRisks.releaseSpots(key);
			return new Downpour(run.granted(), next, null);
		}
		return new Downpour(run.granted(), next, new Volley(spots, now + risk.warnTicks()));
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 고리를 그리고 경고음을 올린다.
	 *
	 * <p>색은 규약의 <b>빨강</b>({@link TrialWarning.Colors#DEADLY})이다 — 이 카드의 고리가 뜻하는
	 * 것이 정확히 「서 있으면 죽는다」이기 때문이다. 색을 새로 만들지 않는다. 색을 넘기지 않는
	 * {@link TrialWarning#markGround(ServerLevel, Vec3, double, int, int)} 가 그 빨강을 쓰므로
	 * 여기서 색을 적을 일도 없고, 그래서 어긋날 수도 없다 — 나눠 그리게 되면서도 <b>색을 넘기지
	 * 않는 형태를 골랐다.</b> 색 인자를 받는 쪽으로 가면 이 파일에 빨강이 적히고, 그때부터
	 * 어긋날 자리가 생긴다.
	 *
	 * <p>고리는 예고 내내 매 틱 손을 댄다. 표식이 남은 신호의 절반이고(자막은 걷어냈다) 사람이
	 * 자기 발밑을 보고 비키는 것이 이 카드의 전부라 성기게 둘 수 없다.
	 * {@link TrialWarning#markGround} 는 거리 제한을 끈 <b>긴 형태</b>로 보낸다 — 짧은 형태는
	 * 32칸에서 잘리는데 아레나 반경이 40 이라, 되돌리는 순간 흩어진 팀원에게는 고리의 절반이
	 * 없는 것이 된다.
	 *
	 * <h2>한 바퀴를 한 틱에 다 그리지 않는다 — 지점이 3배가 되면서 바뀐 것</h2>
	 *
	 * <p>전에는 한 틱에 고리 전체를 그렸다. 10~15곳일 때도 15 × 40 = <b>600점/틱</b>으로 이미
	 * 예산(400)을 넘고 있었고, 45곳이면 <b>1800점</b>이다. 점 하나가 패킷 한 장이고 예고 30틱
	 * 내내 나가므로 그대로 두면 이 카드 하나가 파티클만으로 틱을 민다.
	 *
	 * <p>줄일 수 있는 것이 셋인데 앞의 둘이 막혀 있다. <b>지점 수</b>는 사람이 3배로 정한 값이고,
	 * <b>반경</b>은 피해 범위라 연출 사정으로 못 건드린다. 남은 것이 <b>「한 틱에 얼마나
	 * 그리는가」</b>라서 시간축으로 나눈다 — {@link #markStride} 틱에 걸쳐 한 바퀴를 채우고,
	 * 그 사이 먼저 찍은 점은 아직 살아 있다({@link #MARK_MAX_STRIDE} 에 근거).
	 *
	 * <p>⚠ <b>{@code TrialWarning.ringPoints} 의 하한(40)은 건드리지 않았다.</b> 반경 2.5 에
	 * 40점이면 점 간격 0.39칸으로 지나치게 촘촘한 것이 맞지만, 그 하한은 낙뢰·연쇄 포격·기둥
	 * 화염구를 비롯해 바닥 고리를 쓰는 카드 <b>전부</b>의 모습을 정한다. 한 카드의 예산 때문에
	 * 남의 연출을 바꾸지 않는다.
	 *
	 * <p>고리가 <b>완전해지는 데 {@link #markStride} 틱이 걸린다.</b> 예고가 30틱이라 첫 1/6 만
	 * 성기고 나머지는 예전과 같은 고리다. 첫 틱에도 점은 한 바퀴에 고루 찍히므로 「어디인가」는
	 * 그 틱부터 읽힌다 — 비어 보이는 틱은 없다.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 바닐라 소리 사거리는 볼륨이 1 이하면 16칸이라
	 * 착탄 지점에서 울리면 반대편 사람에게 닿지 않는다. 「연쇄 포격」이 선 80칸 때문에 이미 같은
	 * 답을 쓴다.
	 *
	 * <p>볼리가 열리는 첫 틱에는 층이 바뀌지 않아도 울린다. 예고가 30틱뿐이라 남은 틱이 처음부터
	 * {@link TrialWarning.Stage#MARK} 구간(≤50) 안에서 시작하기 때문이다. 그 판단은 이 파일이
	 * 아니라 {@link TrialRisks#stageJustChanged(int, int)} 가 한다 — 예고 길이
	 * ({@code warnTicks})를 넘겨주기만 하면 된다. 전에는 여기서 따로 막았는데, 예고가 짧은
	 * 카드마다 같은 줄을 다시 적어야 하는 모양이라 한가운데로 옮겼다.
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> members, Downpour run, long now,
			TrialCatalog.Risk.EndRain risk) {
		Volley volley = run.volley();
		if (volley == null) {
			// 볼리 사이의 빈 시간이거나, 방금 터져 land 가 비운 틱이다.
			return;
		}
		int remaining = (int) Math.min(Integer.MAX_VALUE, volley.landsAt() - now);
		if (remaining <= 0) {
			return;
		}
		// 나눠 그린다. 위상을 now 에서 뽑으므로 매 틱 한 칸씩 옮겨 가며 빈자리가 메워진다 —
		// level.getGameTime() 을 부르면 얼어붙은 판에서 같은 몫만 되풀이돼 고리가 안 닫힌다.
		int stride = markStride(risk);
		int phase = markPhase(now, stride);
		for (Vec3 spot : volley.spots()) {
			TrialWarning.markGround(end, spot, risk.radius(), stride, phase);
		}
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null) {
			return;
		}
		if (!TrialRisks.stageJustChanged(remaining, risk.warnTicks())) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.sound(end, member.position(), stage);
		}
	}

	// ------------------------------------------------------------------ 착탄

	/**
	 * 고리 하나가 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p><b>팀에게 한 번만</b> 들어간다. 안에 선 사람을 모두 때리면 넷이 모여 있을 때 40 이 되어
	 * 팀 체력 20 을 두 배 넘긴다 — 함께 움직이는 것이 공유 체력 게임의 올바른 대응인데 그것이
	 * 전멸이 된다. 「연쇄 포격」이 같은 이유로 같은 모양을 쓴다.
	 *
	 * <p>고리끼리는 {@link TrialRisks#reserveSpots} 가 떼어 놓으므로 <b>한 사람이 한 볼리에 한
	 * 발</b>만 맞는다. {@code TrialRisks.worstCaseTickDamage} 가 이 카드를 「피해 × 1」로 세는
	 * 근거가 그 규칙이고, 규칙을 지우면 그 숫자가 거짓이 된다.
	 *
	 * <p>팀원 목록을 직접 돈다. 상자로 후보를 추릴 이유가 없다 — 어차피 한 명만 세고, 팀이 아닌
	 * 사람(관전자·다른 판의 누구)을 때릴 일도 없어야 한다.
	 *
	 * <p>피해원에 실체를 달지 않는다. 엔드 섬 가장자리에서 밀리면 대응 불가 즉사이고 공유 체력이라
	 * 한 사람의 낙사가 팀 전체를 끝낸다. 폭발 피해형을 쓰므로 폭발 보호는 그대로 듣는다 —
	 * 대비한 사람이 손해 보지 않아야 한다.
	 */
	private static void detonate(ServerLevel end, List<ServerPlayer> members, Vec3 at,
			TrialCatalog.Risk.EndRain risk) {
		// 착탄 연출도 긴 형태로 보낸다. 맞는 사람은 어차피 가깝지만 나머지 셋이 「저기 떨어졌다,
		// 피했구나」를 봐야 예고가 완결된다. 아레나 반경 40 이면 흩어진 팀원은 쉽게 32칸을 넘는다.
		end.sendParticles(ParticleTypes.EXPLOSION, true, false, at.x, at.y + 0.2, at.z, 1,
				0.0, 0.0, 0.0, 0.0);
		// 보라색 한 겹을 얹어 「엔더의 것」으로 읽히게 한다. 폭발 파티클만으로는 TNT 로 보인다.
		end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, at.x, at.y + 0.3, at.z, 24,
				risk.radius() * 0.4, 0.2, risk.radius() * 0.4, 0.05);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE,
				SoundSource.HOSTILE, 1.5F, 1.0F);

		for (ServerPlayer member : members) {
			if (!TrialRisks.insideMark(member.position(), at, risk.radius())) {
				continue;
			}
			member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
			// 고리 하나는 한 발이다. 나머지는 같은 발의 두 번째 몫이라 세지 않는다.
			return;
		}
	}

	private static void finish(String key) {
		if (RAINS.remove(key) == null) {
			// 이미 돌려줬다. 두 번 놓는 것 자체는 무해하지만, 여기서 걸러 두면 「끝나는 틱에
			// 정확히 한 번」이 코드에 적힌 사실이 된다.
			return;
		}
		TrialRisks.releaseSpots(key);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 값이 굴릴 수 있는 모양인가.
	 *
	 * <p>{@code TrialRisksTest} 가 카드 값에서 이미 확인하지만, 여기서도 막는 것은 <b>시험이 보는
	 * 것이 카드 목록이지 이 함수의 인자가 아니기</b> 때문이다. 범위가 뒤집힌 값이 들어오면
	 * {@code nextIntBetweenInclusive} 가 터지고, 그 자리는 전투 한가운데다.
	 */
	static boolean usable(TrialCatalog.Risk.EndRain risk) {
		return risk.durationTicks() > 0 && risk.warnTicks() >= 0
				&& risk.minInterval() > 0 && risk.maxInterval() >= risk.minInterval()
				&& risk.minSpots() > 0 && risk.maxSpots() >= risk.minSpots()
				&& risk.radius() > 0.0;
	}

	/**
	 * 아직 비가 내리는 시간인가.
	 *
	 * <p>받은 바로 그 틱({@code elapsed == 0})은 아니다 — 그 틱에는 아직 아무 볼리도 시작되지
	 * 않았다. 마지막 틱({@code elapsed == durationTicks})은 포함한다.
	 */
	static boolean raining(long elapsed, TrialCatalog.Risk.EndRain risk) {
		return elapsed > 0L && elapsed <= risk.durationTicks();
	}

	/**
	 * 지금 여는 볼리의 착탄이 30초 안에 들어오는가.
	 *
	 * <p>이 검사가 「예고는 반드시 착탄으로 끝난다」를 지키는 자리다. 끝나기 직전에 열면 표식만
	 * 뜨고 사라지는 볼리가 생기고, 그러면 사람은 <b>피할 필요가 없었던 자리에서 비킨다</b> —
	 * 한 번이라도 그러면 다음부터 표식을 믿지 않는다.
	 */
	static boolean landsInWindow(long elapsed, TrialCatalog.Risk.EndRain risk) {
		return elapsed + risk.warnTicks() <= risk.durationTicks();
	}

	/** 다음 볼리까지의 간격. 볼리마다 새로 굴린다 — 고정하면 박자를 외워 버린다. */
	static int rolledInterval(RandomSource random, TrialCatalog.Risk.EndRain risk) {
		return random.nextIntBetweenInclusive(risk.minInterval(), risk.maxInterval());
	}

	/** 이번 볼리에 <b>잡아 보려는</b> 지점 수. 실제로 몇 자리가 나오는지는 예약이 정한다. */
	static int rolledSpots(RandomSource random, TrialCatalog.Risk.EndRain risk) {
		return random.nextIntBetweenInclusive(risk.minSpots(), risk.maxSpots());
	}

	/**
	 * 고리 한 바퀴를 몇 틱에 나눠 그릴지.
	 *
	 * <p>「한 바퀴 전부 ÷ 예산」을 올림한 값이고 {@link #MARK_MAX_STRIDE} 에서 멈춘다. 카드
	 * 값에서 뽑으므로 지점 수를 다시 손대는 사람이 <b>패킷도 함께 따라오게</b> 된다.
	 *
	 * <p>⚠ <b>상한에 걸리면 예산을 넘을 수 있다.</b> 파티클 수명이 정한 8틱은 협상할 수 없는
	 * 쪽이라 그 앞에서 멈추는 것이 맞다 — 넘치는지는 {@link #markPoints} 가 숫자로 드러내고
	 * {@code TrialEndRainTest} 가 본다. 지금 값(45곳 × 40점 ÷ 400)은 5 라 상한 안이다.
	 */
	static int markStride(TrialCatalog.Risk.EndRain risk) {
		int perRing = TrialWarning.ringPoints(risk.radius());
		int spots = Math.max(0, risk.maxSpots());
		if (perRing <= 0 || spots <= 0 || MARK_BUDGET <= 0) {
			return 1;
		}
		int whole = spots * perRing;
		int stride = (whole + MARK_BUDGET - 1) / MARK_BUDGET;
		return Math.max(1, Math.min(MARK_MAX_STRIDE, stride));
	}

	/**
	 * 이번 틱에 그릴 몫.
	 *
	 * <p>{@code now} 에서 뽑는다 — {@code level.getGameTime()} 을 부르면 {@code TrialFreeze} 가
	 * 판을 멈춘 동안 같은 몫만 되풀이돼 고리가 영영 안 닫힌다.
	 *
	 * <p>{@code floorMod} 인 이유. 복원 직후에는 {@code now} 가 음수일 수 있고, 그냥 {@code %}
	 * 로 나누면 음수 위상이 나와 {@code markGround} 의 시작 번호가 범위를 벗어난다.
	 */
	static int markPhase(long now, int stride) {
		return (int) Math.floorMod(now, Math.max(1L, stride));
	}

	/**
	 * <b>한 틱에</b> 바닥 표식으로 나가는 점 수의 최대.
	 *
	 * <p>점 하나가 패킷 한 장이고 고리는 예고 30틱 내내 매 틱 나간다. 「상한 안인가」를 숫자로
	 * 물을 수 있게 값에서 직접 뽑는다 — 지점 수나 반경을 고치는 사람은 피해만 보고 패킷은 보지
	 * 않는다.
	 *
	 * <p><b>한 바퀴 전부가 아니라 이번 틱에 찍는 몫</b>이다. 전에는 「지점 수 × 한 바퀴」였고
	 * 15곳에서 600, 45곳이면 1800 이 나왔다 — 그 값이 예산을 넘은 것이 고리를 나눠 그리게 된
	 * 까닭이다({@link #warn} 의 「한 바퀴를 한 틱에 다 그리지 않는다」).
	 *
	 * <p><b>볼리는 언제나 하나뿐</b>이라 이 값이 그대로 최대다. {@code minInterval}(40)이
	 * {@code warnTicks}(30)보다 커서 앞 볼리가 터진 뒤에야 다음 볼리가 열리고,
	 * {@code TrialRisksTest} 가 그 부등식을 카드 값에서 지킨다.
	 */
	static int markPoints(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots())
				* TrialWarning.strokePoints(risk.radius(), markStride(risk));
	}
}
