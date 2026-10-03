package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 엔드 섬 가운데 <b>기반암 단상</b>의 자리. <b>숫자로 적지 않고 바닐라에게 묻는다.</b>
 *
 * <h2>왜 이 파일이 생겼나</h2>
 *
 * <p>사람이 입장 수락창을 정하면서 <b>「엔더 중앙으로 보내 그 기반암 단상있는곳으로 좌표 있을텐데
 * … 0 75 0 이엇나?」</b>라고 했다. <b>물음표가 붙은 기억이지 확정이 아니다.</b> 그런데 실제로
 * {@code DragonTrialManager} 에 {@code (0, 75, 0)} 이 박혀 있었고(사람이 본 것이 그것이다), 그
 * 값은 두 가지가 동시에 틀릴 수 있다.
 *
 * <ul>
 *   <li><b>가로</b> — 바닐라 전투의 원점은 {@code EnderDragon.getFightOrigin()} 이고 그것이
 *       {@code (0, ?, 0)} 이 아닌 판이 있다. {@code TrialLandingShock} 이 그래서 중앙을 박지
 *       않는다</li>
 *   <li><b>세로</b> — 75 는 섬 표면이 아니라 <b>그보다 위의 허공</b>이다. 떨어뜨려도 낙사하지는
 *       않지만(도착 저항 V) 「단상에 모인다」가 아니라 「단상 위로 떨어진다」가 된다</li>
 * </ul>
 *
 * <h2>셈은 바닐라 착지 목표와 같은 것이다</h2>
 *
 * <pre>
 * Vec3.atBottomCenterOf(end.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES,
 *         EnderDragonFight.getPodiumLocation(dragon.getFightOrigin())))
 * </pre>
 *
 * <p>26.3 {@code DragonLandingPhase.doServerTick} 이 쓰는 식이다. 하이트맵을 지나므로 <b>세로가
 * 섬 표면 위 첫 빈 칸</b>이고, 곧 「서 있을 수 있는 자리」다.
 *
 * <h2>✅ 이 셈은 <b>한 벌</b>이다 (2026-10-04 에 모았다)</h2>
 *
 * <p>태어났을 때는 <b>세 벌</b>이었다 — {@code DragonLastStand.podium()}(비공개) ·
 * {@code TrialLandingShock.podiumOf()}(비공개) · 그리고 이 파일. 앞의 둘이 {@code private} 이라
 * 밖에서 부를 수 없어 수락창이 <b>세 번째로 같은 식을 적게 된</b> 것이 이 파일이 생긴 까닭이고,
 * 2026-10-04 에 그 둘이 {@link #locate} 를 부르게 바꿨다. 하이트맵 종류를 바꾸는 날에 고칠 곳이
 * <b>이 함수 하나</b>다.
 *
 * <p>⚠ <b>합치기 전에 셋을 글자 단위로 대조했다.</b> 「같은 것으로 보이는데 다른 것」을 합치는
 * 것이 이 저장소에서 가장 비싼 사고라서다. 위에 적은 식은 <b>셋이 완전히 같았고</b>, 다른 것은
 * <b>「드래곤이 없을 때」 하나뿐</b>이었다.
 *
 * <table border="1">
 *   <caption>드래곤이 없거나 죽어 갈 때 — 셋이 서로 달랐던 유일한 자리</caption>
 *   <tr><th>자리</th><th>무엇을 하는가</th><th>합칠 때</th></tr>
 *   <tr><td>{@link #locate}</td><td>{@code BlockPos.ZERO} 를 원점으로 써 <b>언제나 좌표를
 *       돌려준다</b>(아래 「드래곤이 없을 때」)</td><td>한 벌의 몸이 됐다</td></tr>
 *   <tr><td>{@code DragonLastStand.podium()}</td><td><b>갈래가 없다.</b> 드래곤을 찾은 뒤에만
 *       불린다</td><td>한 줄 껍데기로 남겨 그대로 부른다</td></tr>
 *   <tr><td>{@code TrialLandingShock.podiumOf()}</td><td>드래곤이 없거나 {@code isDeadOrDying()}
 *       이면 <b>{@code null}</b></td><td>⚠ <b>그 문은 안 옮겼다</b> — 셈만 부른다</td></tr>
 * </table>
 *
 * <p>⚠⚠ <b>그 문을 이 파일로 끌어오면 안 된다.</b> 거기는 「방금 앉았는가」를 묻는 자리라
 * <b>죽는 연출 중에 좌표가 멈춘 것을 착지로 읽지 않으려고</b> {@code null} 을 돌린다. 반대로
 * 수락창은 「어디로 모을까」를 묻는 자리라 기본값이 있어야 한다. <b>한 함수가 둘을 다 할 수
 * 없으므로</b> 셈만 합치고 문은 쓰는 쪽에 남겼다.
 *
 * <h2>드래곤이 없을 때</h2>
 *
 * <p>{@link #locate} 는 드래곤이 없으면 {@code BlockPos.ZERO} 를 원점으로 쓴다. 이것은 숫자를
 * 박는 것이 아니다 — 26.3 {@code EnderDragonFight} 의 {@code END_PODIUM_LOCATION} 이
 * <b>정확히 {@code BlockPos.ZERO}</b> 이고({@code static {}} 바이트코드에서 확인했다)
 * {@code getPodiumLocation(origin)} 이 그 둘을 더하는 한 줄이라, 원점을 모를 때 바닐라가 쓰는
 * 기본값과 같은 값이다. 원점을 드래곤 없이 묻는 길은 없다 —
 * {@code EnderDragonFight.origin} 은 {@code private} 이고 조회기가 없다.
 */
public final class TrialPodium {

	/**
	 * 발밑이 이만큼 아래까지는 「딛고 있다」로 본다(블록).
	 *
	 * <p>붙들린 사람이 <b>허공에 떠 있는지</b>를 가르는 값이다. 0 으로 두면 한 칸 뛰어오른 사람도
	 * 허공으로 읽혀 단상으로 끌려가고, 크게 두면 섬 위 20칸을 날던 사람이 「딛고 있다」가 된다.
	 * 4 는 바닐라 낙하 피해가 시작되는 높이(3칸 초과)보다 한 칸 위다 — 곧 <b>이 거리 안이면
	 * 떨어져도 안 아프다</b>가 값의 근거다.
	 */
	static final int SAFE_DROP = 4;

	private TrialPodium() {
	}

	/**
	 * 단상 위에 서는 자리.
	 *
	 * @param dragon 살아 있는 드래곤. {@code null} 이면 바닐라 기본 원점을 쓴다(클래스 설명)
	 */
	public static Vec3 locate(ServerLevel end, @Nullable EnderDragon dragon) {
		BlockPos origin = dragon == null ? BlockPos.ZERO : dragon.getFightOrigin();
		BlockPos podium = EnderDragonFight.getPodiumLocation(origin);
		return Vec3.atBottomCenterOf(
				end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, podium));
	}

	/**
	 * 이 자리가 <b>땅을 딛고 있는가.</b> 거짓이면 허공이다.
	 *
	 * <p>⚠ {@code Entity.onGround()} 를 쓰지 않는다. 그것은 <b>클라이언트가 보내 준 깃발</b>이라
	 * 거짓 보고 한 번에 허공이 땅으로 읽힌다 — {@code DragonLastStandPatterns} 가 공중 판정을
	 * 하이트맵으로 한 번 더 보는 것과 같은 까닭이고, 여기서 틀리면 공유 체력 판에서 한 사람의
	 * 낙사가 곧 팀 전멸이자 월드 삭제다.
	 */
	public static boolean grounded(ServerLevel level, Vec3 at) {
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(at));
		return groundedAt(at.y, top.getY(), level.getMinY());
	}

	/**
	 * {@link #grounded} 의 셈만 떼어 둔 것. 월드 없이 시험한다.
	 *
	 * <p>열이 통째로 비면 하이트맵이 월드 바닥을 돌려주므로 <b>그 값은 땅이 아니다.</b> 그것을
	 * 「아주 먼 땅」으로 읽으면 공허 위에 선 사람이 「딛고 있다」가 된다.
	 *
	 * @param standY 서 있는 세로 좌표
	 * @param topY   그 열에서 하이트맵이 돌려준 세로 좌표
	 * @param minY   월드 바닥
	 */
	static boolean groundedAt(double standY, int topY, int minY) {
		if (topY <= minY) {
			return false;
		}
		return standY - topY <= SAFE_DROP;
	}
}
