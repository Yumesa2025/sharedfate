package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link TrialCatalog.Risk.CrystalGuard} 를 돌린다 — 크리스탈을 깨기 어렵게 만드는 축.
 *
 * <h2>이 카드는 피해를 주지 않는다</h2>
 *
 * <p>같은 풀의 카드가 전부 「맞으면 아프다」면 룰렛이 무엇을 뽑든 같은 판이 된다. 이 카드가
 * 빼앗는 것은 <b>시간</b>이다. 크리스탈이 오래 살아 있을수록 드래곤은 계속 회복하므로, 한 대도
 * 때리지 않고도 전투가 길어진다. <b>여기에 피해를 더하지 말 것</b> — 더하는 순간 이 축이
 * 사라지고 카드 둘이 서로 베낀 것이 된다.
 *
 * <h2>세 갈래는 서로 독립이다</h2>
 *
 * <ul>
 *   <li>{@code arrowImmune} — {@link CrystalWatch} 의 깃발을 세운다. 실제로 막는 일은
 *       {@code EndCrystalGuardMixin} 이 크리스탈 쪽에서 한다.</li>
 *   <li>{@code restoreCage} — 남은 크리스탈에 쇠창살 우리를 다시 세운다.</li>
 *   <li>{@code digSlowdown} — 팀 전원의 <b>채굴 속도를 깎는다</b>
 *       ({@link #DIG_SLOWDOWN_MULTIPLIER}). ⚠⚠ <b>2026-10-01 현재 이것을 켜는 카드가 하나도
 *       없다.</b> 길만 살아 있다 — 걷은 경위와 되살리는 법은 그 상수 설명에 있다.</li>
 * </ul>
 *
 * <p>카드 둘이 각각 {@code (true,false,false)} 「크리스탈 보호막」과
 * {@code (false,true,false)} 「다시 선 쇠창살」이다. 갈래를 값으로 두었으므로 셋을 섞은
 * 셋째 카드는 {@code TrialCatalog} 에 줄 하나를 더 적는 일이 된다.
 *
 * <h2>깃발과 무뎌짐을 되돌리지 않으면 다음 월드까지 샌다</h2>
 *
 * <p>{@link CrystalWatch} 는 정적이라 월드보다 오래 산다. {@code arrowImmune} 을 켠 채 전투가
 * 끝나면 <b>다음 판, 심지어 새로 만든 월드의 크리스탈까지 화살에 맞지 않는다.</b> 그래서
 * {@link #clearState()} 는 조건 없이 깃발을 내린다 — 「내가 켰던가?」를 기억하지 않는 것이
 * 일부러다. 기억하는 순간 그 기억이 어긋날 길이 생긴다.
 *
 * <p>무뎌진 곡괭이도 같은 성질이 되었다. 상태이상이던 때는 지속 시간이 닳으면 바닐라가 지워
 * 주었는데, 이제는 {@link #BLUNTED} 가 사실의 유일한 출처다. 그래서 두 겹으로 막는다 —
 * {@link #clearState()} 가 맵을 비우고, 그것을 못 지나가는 길(서버 강제 종료)에서는
 * {@link #DIG_LAPSE_TICKS} 시효가 스스로 풀어 준다. 지금은 {@code digSlowdown} 을 켜는 카드가
 * 없어 이 맵이 늘 비어 있지만, 되살렸을 때 다시 새지 않도록 둘 다 그대로 둔다.
 *
 * <h2>블록을 놓는 것은 월드를 영구히 바꾸는 일이다</h2>
 *
 * <p>쇠창살은 전투가 끝나도 남는다. 그건 괜찮다 — 바닐라에도 원래 우리가 있는 탑이 있다.
 * 괜찮지 <b>않은</b> 것은 남의 것을 덮는 일이라, 놓을 자리가 <b>공기일 때만</b> 놓는다.
 * 기둥의 흑요석도, 사람이 쌓아 올린 발판도, 이미 서 있는 쇠창살도 건드리지 않는다. 덕분에
 * 「부서진 것만 채운다」가 저절로 성립한다.
 *
 * <p>사람이 손에 들고 다니며 터뜨리는 크리스탈에까지 우리를 세우면 아레나가 쇠창살 밭이
 * 되므로, <b>기둥 위의 크리스탈만</b> 고른다. 판별은 {@link #onSpike} 에 적어 두었다.
 *
 * <h2>주기를 두는 이유</h2>
 *
 * <p>매 틱 블록 75칸 × 크리스탈 10개를 훑으면 전투 내내 헛일을 한다. 크리스탈을 세는 엔티티
 * 조회조차 주기 안으로 넣어 두었으므로, 발동 틱이 아닌 틱에는 깃발 한 번 쓰는 것이 전부다.
 */
public final class TrialCrystalGuard {

	// ------------------------------------------------------------------ 못박아 둔 값

	/**
	 * 채굴 무뎌짐의 속도 배율. 0.85 — 15% 감소.
	 *
	 * <h2>⚠⚠ 2026-10-01 — 이것을 켜는 카드가 하나도 없다</h2>
	 *
	 * <p>세 걸음을 밟아 여기까지 왔다. 아래 문단들은 <b>셋째 걸음 이전의 사실</b>이므로 되살릴
	 * 사람을 위해 남겨 둔 것이고, 지금 도는 이야기가 아니다.
	 *
	 * <ol>
	 *   <li><b>채굴 피로 I</b> 을 걸었다 — 실제로는 70% 감소였다(아래).</li>
	 *   <li>사람이 플레이해 보고 <b>「무딘곡괭이 이거 채굴피로1은 심하고 채굴 속도
	 *       15프로감소로」</b>라고 해서, 상태이상을 걷고 여기 적힌 <b>0.85 를 직접 곱하는</b>
	 *       쪽으로 옮겼다.</li>
	 *   <li><b>2026-10-01 — 사람이 그 15% 마저 통째로 걷었다.</b> 「무딘곡괭이는 채굴감소
	 *       없앳으니 이름 변경해」. {@code sharedfate:iron_cage} 가 {@code digSlowdown} 을
	 *       {@code false} 로 적고 이름이 「다시 선 쇠창살」이 되었다.</li>
	 * </ol>
	 *
	 * <p><b>길을 지우지 않은 까닭.</b> 깎는 자리는 {@code PlayerMiningSpeedMixin} 이고 그것을
	 * {@code mining_speed} 증강이 함께 쓴다. 한쪽을 걷으려다 둘 다 걷으면 증강이 죽는다. 그래서
	 * <b>카드가 선언하는 값만 끄고</b> 길은 그대로 두었다 — 되살리려면 카드에 {@code true} 를
	 * 적는 것으로 끝난다.
	 *
	 * <h2>왜 상태이상이 아닌가</h2>
	 *
	 * <p>예전에는 채굴 피로 I 을 걸었다. 26.3 {@code Player.getDestroySpeed} 의 바이트코드는
	 * 채굴 피로를 {@code 속도 × 0.3^(등급+1)} 로 먹이므로 <b>등급 0 에서도 70% 감소</b>다. 15% 를
	 * 내는 등급은 없고(다음 칸이 91% 감소다), <b>15% 를 내는 바닐라 상태이상도 없다.</b>
	 *
	 * <h2>어디서 깎는가 — 이 저장소가 이미 쓰는 길을 그대로 쓴다</h2>
	 *
	 * <p>26.3 에서 「이 사람이 이 블록을 얼마나 빨리 캐는가」가 한 숫자로 정해지는 곳은
	 * {@code Player.getDestroySpeed(BlockState)} 하나다. 도구 등급·효율·성급함·채굴 피로·물속·
	 * 공중이 전부 거기서 합쳐지고, 진행도를 세는 쪽은 그 결과만 받아 간다.
	 *
	 * <p>그 자리에는 <b>이미 믹스인이 붙어 있다</b> — {@code PlayerMiningSpeedMixin} 이고
	 * {@code mining_speed} 증강(실버 7 「광맥 감각」의 대가)이 그 길로 느려진다. 그래서 믹스인을
	 * 새로 만들지 않았고 {@code sharedfate.mixins.json} 에 <b>더할 줄도 없다.</b> 그쪽 믹스인이
	 * {@link #scaleDestroySpeed} 를 한 줄 더 부른다.
	 *
	 * <p>{@code ServerPlayer} 가 {@code getDestroySpeed} 를 <b>재정의하지 않는다</b>는 것을 26.3
	 * 클래스 파일로 확인했다 — 재정의되는 메서드에 걸린 믹스인은 조용히 죽고 빌드도 로그도
	 * 통과한다(이 저장소가 {@code SlotExpandedLockMixin} 에서 이미 겪은 사고다).
	 *
	 * <h2>⚠ 켤 때 잃는 것 둘</h2>
	 *
	 * <ul>
	 *   <li><b>화면 아이콘이 없다.</b> 상태이상이 아니므로 「곡괭이가 왜 느리지」를 화면에서 읽을
	 *       수 없다. 15% 를 뜻하는 바닐라 아이콘이 없으니 다른 길이 없고, 카드 이름과 설명이 그
	 *       몫을 해야 한다 — 다시 켜는 사람은 <b>카드 이름과 설명도 함께 고칠 것.</b> 지금 이름
	 *       「다시 선 쇠창살」과 설명은 쇠창살만 말한다</li>
	 *   <li><b>클라이언트는 이 배율을 모른다.</b> 서버에서만 곱하므로 클라이언트가 먼저 「다
	 *       캤다」고 판단하고, 서버가 {@code hasDelayedDestroy} 로 붙잡아 자기 진행도를 채운 뒤
	 *       부순다 — <b>블록은 늦게, 그러나 반드시 부서진다.</b> 화면에서는 금이 한 번 되돌아갔다
	 *       다시 부서지는 것이 보인다. 0.85 는 그 지연이 원래 시간의 18%라 눈에 잘 안 띄는
	 *       쪽이다({@code PlayerMiningSpeedMixin} 이 「0.5 아래로는 내리지 않는 편이 좋다」고
	 *       적어 둔 것과 같은 이유다)</li>
	 * </ul>
	 */
	static final double DIG_SLOWDOWN_MULTIPLIER = 0.85;

	/**
	 * 무뎌짐을 다시 선언하는 간격(틱). 2초.
	 *
	 * <p>상태이상이 아니게 되었으므로 이제 「효과를 다시 건다」가 아니라 <b>「아직 살아 있다」를
	 * 적는다</b>는 뜻이다. {@link #DIG_LAPSE_TICKS} 보다 <b>반드시 짧아야</b> 한다 — 같거나 길면
	 * 선언이 닿기 전에 시효가 지나 곡괭이가 주기마다 빨라졌다 느려진다.
	 */
	static final int DIG_REFRESH_INTERVAL = 40;

	/**
	 * 마지막 선언에서 이만큼 지나면 곡괭이가 <b>스스로</b> 돌아온다(틱). 3초.
	 *
	 * <p>{@code TrialHotbarLock.LAPSE_TICKS} 와 같은 장치다. 우리가 한 번이라도 틱을 못 받으면
	 * 그때부터 이 값만큼만 더 무디고 저절로 풀린다 — <b>영영 무뎌진 곡괭이가 생길 수 없는 이유가
	 * 이 한 줄이다.</b> 서버 강제 종료처럼 {@link #clearState()} 를 지나지 못하는 길이 아직 남아
	 * 있고, 상태이상과 달리 이쪽은 바닐라가 대신 지워 주지 않는다.
	 *
	 * <p>{@link #DIG_REFRESH_INTERVAL}(40) 보다 길고 너무 길지 않은 값이다. 전투가 끝나거나
	 * 크리스탈이 전멸하면 이만큼 뒤에 곡괭이가 돌아온다.
	 */
	static final int DIG_LAPSE_TICKS = 60;

	/** 우리를 살피는 간격(틱). 2초. 사람이 깨는 속도보다 훨씬 빠르다. */
	static final int CAGE_REPAIR_INTERVAL = 40;

	/**
	 * 우리의 반폭. 중심에서 ±2칸이라 5×5 가 된다.
	 *
	 * <p>바닐라 {@code EndSpikeFeature.placeSpike} 의 {@code -2..2} 와 같은 값이다.
	 */
	static final int CAGE_HALF_WIDTH = 2;

	/**
	 * 우리의 지붕 높이. 바닥(기둥 꼭대기)에서 위로 3칸.
	 *
	 * <p>바닐라의 {@code 0..3} 과 같다. 크리스탈은 바닥에서 1칸 위에 뜬다.
	 */
	static final int CAGE_TOP = 3;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 곡괭이가 무뎌진 사람들 → 마지막으로 그것을 선언한 틱.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 상태이상을 걸던 때는
	 * 바닐라가 상태를 들어 주었지만, 직접 깎는 쪽으로 옮긴 뒤로는 <b>「누가 무뎌져 있는가」를 우리가
	 * 들어야 한다.</b>
	 *
	 * <p>열쇠가 {@link ServerPlayer} 가 아니라 <b>UUID</b> 인 것이 중요하다. 죽어서 개체가 갈려도
	 * 리스폰한 사람에게 그대로 붙어 있고, 재접속하면 명단에서 빠졌다가 돌아온 첫 틱에 다시 선언된다.
	 *
	 * <p>⚠ <b>이번 명단에 없는 열쇠를 지우는(retainAll) 방식을 쓰지 말 것.</b> 이 맵은 팀이 아니라
	 * 사람으로 열쇠를 잡는데 {@link #tick} 은 팀마다 따로 불린다 — 두 팀이 동시에 엔드에 있으면
	 * A 팀의 틱이 B 팀의 선언을 지운다. {@code TrialHotbarLock.LOCKS} 가 같은 함정을 같은 방법으로
	 * (시각으로) 피한다.
	 */
	private static final java.util.Map<java.util.UUID, Long> BLUNTED = new java.util.HashMap<>();

	private TrialCrystalGuard() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱. {@code DragonTrialManager} 가 이 카드를 들고 있는 팀마다 부른다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param dragon  이 카드는 드래곤을 건드리지 않는다. 위험 실행기들이 <b>같은 모양의 진입점</b>을
	 *                갖게 하려고 받아만 둔다 — 실행기마다 인자가 다르면 배선하는 쪽이 갈래마다
	 *                다른 호출을 적게 되고, 그것이 곧 「새 카드를 배선에서 빠뜨리는」 길이다
	 * @param granted 이 카드를 받은 틱. 주기는 <b>월드 시간이 아니라 여기서부터</b> 센다
	 */
	public static void tick(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, String key, long granted, long now,
			TrialCatalog.Risk.CrystalGuard risk) {
		if (end == null || risk == null) {
			return;
		}

		if (risk.arrowImmune()) {
			// 크리스탈이 다 깨진 뒤에도 계속 선언한다. 「부활」 카드가 되살릴 수 있고, 그때
			// 보호막만 사라져 있으면 두 카드가 서로를 지운 꼴이 된다.
			CrystalWatch.setArrowImmune(true);
		}

		boolean cageDue = risk.restoreCage() && refreshesAt(now, granted, CAGE_REPAIR_INTERVAL);
		boolean digDue = risk.digSlowdown() && refreshesAt(now, granted, DIG_REFRESH_INTERVAL);
		if (!cageDue && !digDue) {
			return;
		}

		// 여기서만 엔티티를 훑는다. 발동 틱이 아니면 위에서 이미 돌아갔다.
		List<? extends EndCrystal> crystals =
				end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive);
		if (crystals.isEmpty()) {
			// 전멸 뒤에는 아무 일도 하지 않는다. 지킬 것이 없는데 사람의 곡괭이만 무뎌지면
			// 그건 시간을 빼앗는 것이 아니라 그냥 괴롭히는 것이다.
			return;
		}

		if (cageDue) {
			for (EndCrystal crystal : crystals) {
				restoreCage(end, crystal);
			}
		}
		if (digDue && members != null) {
			for (ServerPlayer member : members) {
				// 걸어 두는 것이 아니라 「아직 무디다」를 적는다. 실제로 깎는 자리는
				// scaleDestroySpeed 이고, 적힌 시각이 오래되면 저절로 풀린다(DIG_LAPSE_TICKS).
				BLUNTED.put(member.getUUID(), now);
			}
			// 오래된 선언을 버린다. 접속을 끊은 사람과 사라진 팀이 여기서 함께 정리된다.
			// 남아 있어도 해는 없다 — 읽는 쪽이 시각을 본다. 이것은 살림일 뿐이다.
			BLUNTED.values().removeIf(seenAt -> now - seenAt > DIG_LAPSE_TICKS);
		}
	}

	// ------------------------------------------------- 채굴 무뎌짐 (지금 켜는 카드가 없다)

	/**
	 * {@code Player.getDestroySpeed} 가 내놓은 값에 무뎌짐을 먹인다.
	 *
	 * <p>{@code PlayerMiningSpeedMixin} 이 {@code mining_speed} 증강 다음에 부른다. 무뎌져 있지
	 * 않거나 서버 쪽 플레이어가 아니면 받은 값을 <b>그대로</b> 돌려주므로 바닐라와 완전히 같다.
	 *
	 * <p>⚠ <b>2026-10-01 현재 {@link #BLUNTED} 가 늘 비어 있다</b> — {@code digSlowdown} 을 켜는
	 * 카드가 없어 아무도 여기서 깎이지 않는다. 그래도 이 줄을 믹스인에서 떼지 않은 까닭은 그
	 * 믹스인을 {@code mining_speed} 증강이 함께 쓰기 때문이다 — 한쪽을 걷으려다 둘 다 걷으면
	 * 증강이 죽는다. 비어 있는 맵을 한 번 들여다보는 비용뿐이다.
	 *
	 * <p>{@code holder.level().getGameTime()} 을 여기서 직접 묻는다. 다른 시련 코드가 받은
	 * {@code now} 를 쓰는 것과 다른데, 이 자리는 <b>시련의 틱이 아니라 블록을 캐는 길</b>이라
	 * 넘겨받을 {@code now} 가 없다. {@code TrialHotbarLock.stiff} 도 같은 이유로 같은 것을 부른다.
	 *
	 * @param base {@code getDestroySpeed} 의 원래 반환값
	 * @return 배율을 먹인 값. 해당 없으면 {@code base} 그대로
	 */
	public static float scaleDestroySpeed(@Nullable net.minecraft.world.entity.player.Player player,
			float base) {
		if (!(base > 0.0F) || !Float.isFinite(base) || !(player instanceof ServerPlayer holder)) {
			return base;
		}
		Long seenAt = BLUNTED.get(holder.getUUID());
		if (seenAt == null) {
			return base;
		}
		long since = holder.level().getGameTime() - seenAt;
		if (since < 0L || since > DIG_LAPSE_TICKS) {
			return base;
		}
		float scaled = (float) (base * DIG_SLOWDOWN_MULTIPLIER);
		// 0 이나 음수가 되면 그 블록을 영영 캘 수 없다. 이 카드는 시간을 빼앗는 것이지
		// 채굴을 막는 것이 아니다 — 그럴 바에는 원래 값이 낫다.
		return Float.isFinite(scaled) && scaled > 0.0F ? scaled : base;
	}


	/**
	 * 월드가 바뀌거나 전투가 끝날 때.
	 *
	 * <p><b>무뎌진 곡괭이도 여기서 전부 돌려놓는다.</b> 상태이상이던 때는 지속 시간이 닳으면
	 * 바닐라가 알아서 지웠지만, 직접 깎는 쪽으로 옮긴 뒤로는 우리 맵이 곧 사실이다. 여기는
	 * {@code SERVER_STOPPED} 에서도 불려 월드를 만질 수 없는데, 이 맵을 비우는 것은 월드를
	 * 건드리지 않으므로 그 자리에서도 안전하다 — <b>사람에게 아무것도 걸어 두지 않았다</b>는
	 * 것이 이 방식의 값어치다.
	 *
	 * <p><b>깃발을 조건 없이 내린다.</b> 놓은 쇠창살은 되돌리지 않는다 — 월드에 남은 블록을
	 * 나중에 지우려면 「우리가 놓은 것」을 기억해야 하는데, 그 기억은 서버 재시작 한 번으로
	 * 어긋나고 어긋난 기억은 남의 건축을 지운다. 바닐라에도 우리가 있는 탑이 있으므로 남아도
	 * 이상하지 않다.
	 *
	 * <p>깬 사람 기록은 여기서 지우지 않는다. 그 기록의 주인은 이 카드가 아니라 판 자체라
	 * {@link CrystalWatch#clearState()} 가 맡는다.
	 */
	public static void clearState() {
		CrystalWatch.setArrowImmune(false);
		BLUNTED.clear();
	}

	// ------------------------------------------------------------------ 쇠창살 우리

	/**
	 * 크리스탈 하나에 우리를 세운다. 이미 서 있는 것은 건드리지 않는다.
	 *
	 * <p>바닐라는 <b>우리가 있는 탑과 없는 탑</b>을 섞어 만든다({@code EndSpike.isGuarded}).
	 * 이 카드는 <b>없던 탑에도</b> 세운다 — 그것이 카드 이름이 약속하는 바다.
	 */
	private static void restoreCage(ServerLevel end, EndCrystal crystal) {
		BlockPos base = spikeTop(end, crystal);
		if (base == null) {
			return;
		}
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dx = -CAGE_HALF_WIDTH; dx <= CAGE_HALF_WIDTH; dx++) {
			for (int dz = -CAGE_HALF_WIDTH; dz <= CAGE_HALF_WIDTH; dz++) {
				for (int dy = 0; dy <= CAGE_TOP; dy++) {
					if (!cagePart(dx, dz, dy)) {
						continue;
					}
					at.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
					if (!end.getBlockState(at).isAir()) {
						// 공기가 아니면 남의 것이거나 이미 채워진 것이다. 둘 다 그냥 둔다.
						continue;
					}
					// UPDATE_CLIENTS 만 준다. 이웃 갱신을 돌리면 바닐라 연결 규칙이
					// 우리가 적어 넣은 모양을 다시 계산해 버린다.
					end.setBlock(at, cageState(dx, dz, dy), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/**
	 * 이 크리스탈이 앉아 있는 <b>기둥 꼭대기</b>. 기둥 위가 아니면 {@code null}.
	 *
	 * <p>바닐라가 만드는 모양은 이렇다 — 흑요석이 {@code y < height} 를 채우고,
	 * {@code y == height} 에 <b>기반암</b> 한 칸, 그 위 {@code height + 1} 에 크리스탈이 뜬다.
	 * 우리는 {@code height} 부터 {@code height + 3} 까지 놓인다. 그래서 크리스탈의 한 칸 아래가
	 * 곧 우리의 바닥이다.
	 *
	 * <p>「기반암 아래 흑요석」 두 칸을 함께 보는 이유는 <b>사람이 손으로 놓은 크리스탈</b>을
	 * 걸러 내기 위해서다. 크리스탈은 전투 중에 폭탄으로 쓰는 물건이라, 그것까지 우리로 감싸면
	 * 아레나 바닥이 쇠창살로 뒤덮이고 사람이 갇힌다. 나가는 문의 기반암 위에 놓은 것도 아래가
	 * 흑요석이 아니라 여기서 걸린다.
	 */
	private static @Nullable BlockPos spikeTop(ServerLevel end, EndCrystal crystal) {
		BlockPos base = crystal.blockPosition().below();
		if (!end.getBlockState(base).is(Blocks.BEDROCK)) {
			return null;
		}
		if (!end.getBlockState(base.below()).is(Blocks.OBSIDIAN)) {
			return null;
		}
		return base;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 받은 뒤 흐른 틱이 이 주기의 배수인가. {@code 0} 도 포함한다.
	 *
	 * <p>{@link TrialRisks#firesAt} 와 달리 <b>받은 그 틱에 곧바로 참</b>이다. 그쪽은 예고
	 * 없이 터지는 것을 막으려고 첫 주기를 비우지만, 이 카드는 피해를 주지 않으므로 예고할
	 * 것이 없다. 오히려 늦게 걸리면 「쇠창살이 생긴다」고 읽은 직후 2초 동안 아무 일도 일어나지
	 * 않아 카드가 고장 난 것처럼 보인다.
	 *
	 * <p>주기를 {@code granted} 에서 세는 이유는 {@code TrialRisks} 와 같다 — 월드 시간으로
	 * 세면 카드마다 위상이 겹치고, 복원 직후 {@code now < granted} 일 때 음수로 떨어진다.
	 */
	static boolean refreshesAt(long now, long granted, int interval) {
		if (interval <= 0) {
			return false;
		}
		return TrialRisks.elapsedSinceGrant(now, granted) % interval == 0L;
	}

	/**
	 * 이 자리에 우리 블록이 들어가는가. 중심 기준 상대 좌표다.
	 *
	 * <p>바닐라 {@code EndSpikeFeature.placeSpike} 의 판정 그대로 — <b>네 벽면이거나 지붕</b>
	 * 이면 참이다. 5×5×4 = 100칸 중 안쪽 3×3×3 = 27칸이 비고 73칸이 남는다. 바닥 한가운데
	 * ({@code 0,0,0})가 비는 덕분에 크리스탈을 받치는 기반암을 덮지 않는다.
	 */
	static boolean cagePart(int dx, int dz, int dy) {
		boolean edgeX = Mth.abs(dx) == CAGE_HALF_WIDTH;
		boolean edgeZ = Mth.abs(dz) == CAGE_HALF_WIDTH;
		boolean roof = dy == CAGE_TOP;
		return edgeX || edgeZ || roof;
	}

	/**
	 * 그 자리에 놓을 쇠창살의 <b>연결 모양</b>.
	 *
	 * <p>바닐라와 글자 그대로 같은 식이다. 축이 엇갈려 보이는 것은 맞다 — {@code x = ±2} 벽에
	 * 선 창살은 <b>z 방향으로</b> 이어지므로 북·남이 켜지고, {@code z = ±2} 벽은 그 반대다.
	 * 지붕({@code roof})은 네 방향이 모두 켜져 격자가 된다.
	 *
	 * <p>모양을 직접 적는 이유는 이웃 갱신 없이 놓기 때문이다. 바닐라에 계산을 맡기면 아직
	 * 놓이지 않은 이웃을 보고 끊긴 창살이 된다.
	 */
	static BlockState cageState(int dx, int dz, int dy) {
		boolean roof = dy == CAGE_TOP;
		boolean wallAlongZ = dx == -CAGE_HALF_WIDTH || dx == CAGE_HALF_WIDTH || roof;
		boolean wallAlongX = dz == -CAGE_HALF_WIDTH || dz == CAGE_HALF_WIDTH || roof;
		return Blocks.IRON_BARS.defaultBlockState()
				.setValue(IronBarsBlock.NORTH, wallAlongZ && dz != -CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.SOUTH, wallAlongZ && dz != CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.WEST, wallAlongX && dx != -CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.EAST, wallAlongX && dx != CAGE_HALF_WIDTH);
	}

}
