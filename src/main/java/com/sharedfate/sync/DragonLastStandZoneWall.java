package com.sharedfate.sync;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 「최후의 저항」 안전지대의 <b>입자 벽</b>. 원 둘레에 사람 키만 한 빨간 기둥을 세운다.
 *
 * <h2>왜 생겼는가 — 월드 보더를 버렸다 (2026-10-04)</h2>
 *
 * <p>사람 말: <b>「안전지대가 사실상 보더라 나갈 수가 없어. 이거 좀 이상함」</b>. 고른 선택지가
 * <b>「나갈 수 있는 원으로」</b> — 보더 없이 원만 그리고, 밖으로 걸어 나갈 수 있되 나가 있는 동안
 * 피해를 받는다(배틀로얄 자기장 방식). 바닐라 보더는 <b>안에 있는 사람을 막는 벽</b>이라 「밖」이
 * 축소가 사람을 지나쳐 간 순간 말고는 생기지 않았고, 그래서 밖 피해가 거의 뜻이 없었다. 판정은
 * {@link DragonLastStandZone#outside} 가 같은 반경으로 하고, 여기는 <b>보이는 것</b>만 맡는다.
 *
 * <h2>색 — 규약의 빨강 {@code DEADLY}, 재질은 「서 있는 기둥」</h2>
 *
 * <p>원 밖은 「서 있으면 맞는다」라 규약의 빨강이다({@code docs/드래곤-시련-카드.md} 「표식 색 규약」).
 * <b>다섯째 색을 만들지 않았다.</b> 같은 빨강을 쓰는 표식(부채꼴 테두리 · 흡입 경계 고리)과는
 * <b>모양</b>으로 갈린다.
 *
 * <ul>
 *   <li>그쪽은 <b>바닥에 눕힌 선</b> + 흰 {@code CRIT} 벽이고, 이쪽은 <b>빨간 점이 세 층으로 쌓인
 *       기둥</b>이다 — 흰 점이 하나도 없다</li>
 *   <li>그쪽은 패턴 동안만 뜨고, 이쪽은 진입부터 끝까지 <b>언제나</b> 서 있다</li>
 *   <li>마지막 반경 12 가 날개 퍼덕이기의 바깥 고리(반경 12, 파랑 바닥 + 흰 벽)와 겹치지만 색도
 *       재질도 다르다</li>
 * </ul>
 *
 * <p>⚠ <b>{@code CRIT} 으로 세우는 수법(개수 0 → 속도)을 못 쓴다.</b> {@code CRIT} 은 흰색이고
 * 색을 못 바꾼다. 먼지는 26.3 {@code DustParticleBase} 가 받은 속도에 0.1 을 곱해 버려 위로
 * 쏘아 올릴 수 없다(바이트코드 확인). 그래서 <b>높이를 점 수로 산다</b> — 기둥 하나가 점 셋이다.
 *
 * <h2>점 예산 — 둘레를 {@value #STRIDE} 틱에 나눠 세운다</h2>
 *
 * <p>{@code docs/드래곤-트라이얼.md} 5장의 예산이 한 틱 400~440 이고, 이 페이즈의 최악이
 * <b>패턴 322 + 오브젝트 파도 37 = 359</b> 다. 이 벽은 그 위에 <b>언제나</b> 얹히므로
 * <b>400 아래</b>를 지키도록 잡았다 — 반경 42 에서 {@link #worstPointsPerTick()} = <b>39</b>,
 * 합 <b>398</b>. {@code DragonLastStandZoneWallTest} 가 합을 남의 값에서 직접 읽어 못박는다.
 *
 * <table border="1">
 *   <caption>반경 42 에서</caption>
 *   <tr><th>값</th><th>수</th><th>근거</th></tr>
 *   <tr><td>둘레</td><td>263.9칸</td><td>2π × 42</td></tr>
 *   <tr><td>기둥 간격 {@link #POST_GAP}</td><td>1.4칸</td><td>기둥 189개</td></tr>
 *   <tr><td>나눠 세우기 {@link #STRIDE}</td><td>15틱</td><td>한 틱에 기둥 13개</td></tr>
 *   <tr><td>기둥 하나의 점 {@link #POST_HEIGHTS}</td><td>3</td><td>0.3 · 1.0 · 1.7칸 — 사람 키</td></tr>
 *   <tr><td>한 틱</td><td><b>39점</b></td><td>13 × 3. 패킷도 39장(전부 개수 1 의 긴 형식)</td></tr>
 * </table>
 *
 * <p>⚠ <b>나눠 세우기의 상한은 먼지 수명이다.</b> 26.3 {@code DustParticleBase} 의 수명이
 * {@code max(1, (int)(8 ÷ (난수×0.8 + 0.2)) × scale)} 라 {@link #DUST_SCALE} 2.0 이면 <b>최소
 * 16틱</b>이다. {@value #STRIDE} 틱마다 같은 기둥을 다시 세우므로 먼저 세운 점이 아직 살아 있다 —
 * {@code TrialWarning.markGround} 의 「stride 는 수명보다 작아야 한다」와 같은 규칙이고, 크기를
 * 2 로 올려 그 상한을 8 에서 16 으로 늘린 것이다. 크기를 줄이면 시험이 먼저 멈춘다.
 *
 * <p>⚠ 대가는 <b>「살아 있는 점」</b>이다. 크기가 수명에도 곱해져 평균 수명이 약 32틱이라 화면에
 * 남는 점이 약 1,250개다(39 × 32). 문서의 「선은 9,600」 기준 안이다.
 *
 * <h2>⚠ 줄어드는 동안은 <b>조금 앞서</b> 그린다 — 표식이 안전한 쪽으로만 틀린다</h2>
 *
 * <p>한 기둥을 {@value #STRIDE} 틱에 한 번만 다시 세우므로, 지금 반경으로 그리면 줄어드는 동안
 * 기둥이 실제 경계보다 최대 1.5칸(15틱 × 초당 2칸) <b>바깥</b>에 남는다. 그러면 벽 안쪽에 선 사람이
 * 사실은 밖이라 아프다 — 「표식이 거짓말하지 않는다」가 깨진다. 그래서 그 기둥을 <b>다음에 다시
 * 세울 때의 반경</b>({@code radiusAt(elapsed + STRIDE)})으로 그린다. 그러면 가장 새 기둥은 언제나
 * 실제 경계와 같거나 <b>안쪽</b>에 서고, 틀려도 「안인데 밖처럼 보인다」 쪽으로만 틀린다. 축소가 없는
 * 구간에서는 두 반경이 같아 아무 일도 하지 않는다. 앞서 세운 기둥은 수명이 다할 때까지 바깥에
 * 남지만, 그 자리는 이미 밖이라 빨강이 맞다.
 *
 * <h2>땅이 없는 자리 — 포디움 높이에 띄운다</h2>
 *
 * <p>반경 42 원은 섬 끝(약 40)을 넘어 허공 위를 지난다. 그 자리도 <b>지대 밖으로 가는 길</b>이라
 * (다리를 놓거나 겉날개로 나가는 사람) 기둥을 지우지 않고 <b>지대 중심의 높이</b>(드래곤을 못박아 둔
 * 포디움 맨 위)에 세운다. 섬 위에서는 그 칸의 지표 위다 — {@code TrialEnderPulse.Ground} 를 그대로
 * 부른다(진입 연출의 {@code pullTarget} 과 같은 도우미).
 */
final class DragonLastStandZoneWall {

	/**
	 * 기둥 사이 간격(칸).
	 *
	 * <p>점 예산에서 거꾸로 셌다 — 반경 42 에서 한 틱 39점이 되는 가장 좁은 간격이다. 1.3 이면
	 * 기둥 203개 · 한 틱 14개 · 42점이라 합이 401 로 400 을 넘는다.
	 */
	static final double POST_GAP = 1.4;

	/**
	 * 둘레를 몇 틱에 나눠 세우는가.
	 *
	 * <p>먼지 최소 수명(크기 2.0 에서 16틱)보다 <b>작아야</b> 기둥이 끊기지 않는다 — 클래스 설명.
	 */
	static final int STRIDE = 15;

	/**
	 * 먼지 크기. 2.0 이 바닐라 레드스톤 가루({@code TrialWarning.dust}, 1.0)의 두 배다.
	 *
	 * <p>키운 까닭은 둘이다 — ① 수명이 함께 두 배라 {@link #STRIDE} 를 15 까지 늘릴 수 있고, 그것이
	 * 둘레 264칸을 점 39개로 덮는 유일한 길이다 ② 이 벽은 <b>멀리서</b> 읽혀야 한다(42칸 저편의
	 * 경계를 보고 돌아올지 정한다).
	 */
	static final float DUST_SCALE = 2.0F;

	/**
	 * 기둥 하나에 쌓는 점의 높이(지표에서 칸). 셋이고 맨 위가 1.7 — <b>사람 키(1.8)</b>다.
	 *
	 * <p>사람이 「입자 벽」이라고 했다. 바닥 선만 그리면 서서 보는 눈높이에서 시선과 나란해 거의 안
	 * 보인다 — 「엔더 파동」과 「착지 충격」이 같은 지적을 받고 벽으로 세웠다.
	 */
	static final double[] POST_HEIGHTS = {0.3, 1.0, 1.7};

	private DragonLastStandZoneWall() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 이 반경 둘레에 세우는 기둥 수. */
	static int postsAt(double radius) {
		if (!(radius > 0.0)) {
			return 0;
		}
		return Math.max(1, (int) Math.ceil((Math.PI * 2.0 * radius) / POST_GAP));
	}

	/**
	 * 이 반경에서 한 틱에 나가는 점 수의 <b>상한</b>.
	 *
	 * <p>나눠 세우기는 위상에 따라 하나 더 나갈 수 있으므로 올림이다 — 예산은 늘 나쁜 쪽을 본다.
	 */
	static int pointsPerTickAt(double radius) {
		int posts = postsAt(radius);
		return ((posts + STRIDE - 1) / STRIDE) * POST_HEIGHTS.length;
	}

	/**
	 * 한 틱에 가장 많이 나가는 점 수. 반경이 가장 클 때(시작 42)다.
	 *
	 * <p>{@code DragonLastStandPatterns.worstCasePointsPerTick} 과
	 * {@code DragonLastStandObjects.worstCasePointsPerTick} 위에 <b>더한다</b> — 이 벽은 패턴·파도와
	 * 언제나 함께 돈다.
	 */
	static int worstPointsPerTick() {
		double widest = 0.0;
		for (double radius : DragonLastStandZone.RADII) {
			widest = Math.max(widest, radius);
		}
		return pointsPerTickAt(widest);
	}

	/**
	 * 이 틱에 기둥을 세울 반경. 줄어드는 동안은 <b>다음에 다시 세울 때의 반경</b>이다.
	 *
	 * <p>까닭은 클래스 설명의 「조금 앞서 그린다」. 판정 반경({@link DragonLastStandZone#radiusAt})과
	 * 같거나 <b>작기만</b> 하다 — 시험이 115초를 통째로 훑어 본다.
	 */
	static double drawRadiusAt(long elapsed) {
		return DragonLastStandZone.radiusAt(elapsed + STRIDE);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 이 틱 몫의 기둥을 세운다. {@link DragonLastStandZone#tick} 이 매 틱 부른다.
	 *
	 * <p>진입 연출 동안(시계가 음수)에도 부른다 — 사람이 정한 순서가 <b>「보더가 생기면서」</b>라
	 * 진입한 그 틱부터 원이 보여야 한다. 그때 반경은 시작값 42 다.
	 *
	 * @param center  지대의 중심. 드래곤을 못박아 둔 자리다
	 * @param elapsed 시계가 돈 틱. 음수면 연출 중이다
	 * @param now     받은 틱. 나눠 세우기의 위상을 여기서 뽑는다
	 */
	static void draw(ServerLevel end, Vec3 center, long elapsed, long now) {
		double radius = drawRadiusAt(elapsed);
		int posts = postsAt(radius);
		if (posts <= 0) {
			return;
		}
		ParticleOptions dust = new DustParticleOptions(TrialWarning.Colors.DEADLY, DUST_SCALE);
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		// floorMod 라야 음수 now 에서도 0..STRIDE-1 로 떨어진다(TrialWarning.markGround 와 같다).
		for (int index = Math.floorMod(now, STRIDE); index < posts; index += STRIDE) {
			double angle = (Math.PI * 2.0 * index) / posts;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			int surface = ground.surfaceAt(end, x, z);
			double base = surface == TrialEnderPulse.NO_GROUND ? center.y : surface;
			for (double height : POST_HEIGHTS) {
				// 긴 형식이다. 짧은 형식은 32칸에서 잘려 42칸 저편의 벽이 안 보인다.
				end.sendParticles(dust, true, false, x, base + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}
