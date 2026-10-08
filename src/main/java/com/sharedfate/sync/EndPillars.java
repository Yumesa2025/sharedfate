package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔드 흑요석 기둥의 자리.
 *
 * <h2>왜 좌표를 박지 않는가</h2>
 *
 * <p>기둥 열 개의 배치는 <b>월드 시드에서 나온다.</b> 높이도 반경도 쇠창살 유무도 시드마다 다르다.
 * 어느 판에서 재어 적어 둔 좌표는 다음 판에서 허공을 가리킨다 — 거기서 불덩이가 날아오면 플레이어
 * 눈에는 아무것도 없는 하늘에서 오는 것이 된다. 바닐라가 쓰는 것과 <b>같은 목록</b>을 같은 자리에서
 * 받아 온다.
 *
 * <h2>26.3 에서는 {@code EndSpikeFeature} 다</h2>
 *
 * <p>예전 판의 {@code SpikeFeature} 가 둘로 갈렸다. 지금 {@code SpikeFeature} 는 유황 가시 같은
 * 일반 가시 지형이고, 엔드 기둥은 {@link EndSpikeFeature} 가 들고 있다. 이름만 보고 옛 클래스를
 * 부르면 엉뚱한 지형을 묻게 되므로 옮길 때 반드시 확인할 것.
 *
 * <h2>높이 규약은 바닐라 생성기에서 읽어 왔다</h2>
 *
 * <p>{@code EndSpikeFeature.placeSpike} 는 흑요석을 {@code y < height} 까지만 채우고, 크리스탈을
 * {@code (centerX + 0.5, height + 1, centerZ + 0.5)} 에 놓은 뒤 그 아래({@code y = height})에
 * 기반암을 깐다. 그래서 <b>기둥 윗면은 {@code height}</b> 이고 <b>크리스탈 자리는
 * {@code height + 1}</b> 이다. 이 두 줄이 어긋나면 발사점이 기둥 속에 파묻히거나 부활한 크리스탈이
 * 공중에 뜬다.
 *
 * <h2>상태를 들지 않는다</h2>
 *
 * <p>화염구와 크리스탈 부활이 함께 쓴다. 한쪽이 캐시를 비우면 다른 쪽이 빈 목록을 받는 사고를
 * 아예 만들지 않으려고 매번 계산한다. 바닐라 쪽이 이미 시드로 캐시하고 있어 비싸지도 않다.
 */
public final class EndPillars {

	private EndPillars() {
	}

	/**
	 * 기둥 꼭대기 중앙들. 기둥이 없으면 빈 목록.
	 *
	 * <p>돌려주는 {@code y} 는 <b>딛고 설 수 있는 윗면</b>이다. 블록 좌표가 아니라 면의 높이이므로
	 * 그대로 파티클을 찍으면 기둥에 파묻히지 않는다.
	 */
	public static List<Vec3> tops(@Nullable ServerLevel end) {
		List<Vec3> tops = new ArrayList<>();
		if (end == null) {
			return tops;
		}
		for (EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(end)) {
			tops.add(new Vec3(spike.getCenterX() + 0.5, spike.getHeight(), spike.getCenterZ() + 0.5));
		}
		return tops;
	}

	/**
	 * 그 자리에서 가장 가까운 기둥 꼭대기. 없으면 {@code null}.
	 *
	 * <p>거리는 세로까지 넣어 잰다. 기둥은 높이가 제각각이라 바닥에서만 재면 <b>바로 옆의 낮은
	 * 기둥보다 멀리 있는 높은 기둥이 더 가깝다고 나오는</b> 일이 생기고, 그러면 불덩이가 화면
	 * 반대쪽에서 날아온다.
	 */
	public static @Nullable Vec3 nearestTop(@Nullable ServerLevel end, @Nullable Vec3 from) {
		return nearestOf(tops(end), from);
	}

	/**
	 * 크리스탈이 놓이는 자리 (꼭대기 바로 위 한 칸).
	 *
	 * <p>바닐라가 크리스탈을 두는 좌표와 <b>같은 값</b>이다. 여기에 그대로 놓으면 처음 생성된
	 * 크리스탈과 구분이 가지 않는다 — 부활한 것만 반 칸 어긋나 있으면 조준이 달라진다.
	 */
	public static List<Vec3> crystalSeats(@Nullable ServerLevel end) {
		List<Vec3> seats = new ArrayList<>();
		for (Vec3 top : tops(end)) {
			seats.add(new Vec3(top.x, top.y + 1.0, top.z));
		}
		return seats;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 목록에서 가장 가까운 점. 목록이 비었으면 {@code null}. */
	static @Nullable Vec3 nearestOf(List<Vec3> candidates, @Nullable Vec3 from) {
		if (from == null || candidates.isEmpty()) {
			return null;
		}
		Vec3 best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Vec3 candidate : candidates) {
			double distance = candidate.distanceToSqr(from);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}
}
