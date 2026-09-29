package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 시련 카드 목록.
 *
 * <h2>왜 아직 JSON 이 아닌가</h2>
 *
 * <p>증강처럼 JSON 으로 빼는 것이 목표다. 다만 위험 타입마다 필요한 값이 다르고 그 목록이
 * 아직 두어 개 분량밖에 드러나지 않았다. 지금 파서를 만들면 카드 대여섯 장째에 다시 짠다.
 * <b>타입이 다섯쯤 굳은 뒤에 옮긴다.</b>
 *
 * <h2>카드는 룰렛으로 뽑는다</h2>
 *
 * <p>셋을 띄워 고르게 하지 않는다. 자리가 터지고 {@linkplain Trigger#delayTicks() 그 자리가 정한
 * 만큼} 지나면 <b>판이 멈추고</b> 이름이 돌다가 멈추며, 멈춘 것이 그 판의 시련이다. 카드가 한
 * 장뿐일 때도 굴러간다 — 고르게 하려면 자리당 최소 세 장이 있어야 하는데 카드를 채워 가는
 * 동안에는 맞출 수 없는 조건이다.
 *
 * <p>처음에는 타이틀 글자만 갈아 끼워 규약을 올리지 않았다. 그런데 <b>드래곤이 때리는 중에
 * 화면 위로 지나가는 글자는 읽히지 않았다.</b> 무엇을 받았는지 모른 채 싸우게 되고 전멸은 곧
 * 월드 삭제라, 증강처럼 판을 멈추고 화면을 띄우기로 했다({@link TrialFreeze}). 그 대가로
 * <b>통신 규약이 30</b> 이다.
 *
 * <h2>풀을 나누는 이유</h2>
 *
 * <p>입장 직후와 체력 30% 는 같은 무게일 수 없다. 트리거마다 풀을 따로 두면 <b>난이도 곡선</b>이
 * 생긴다. 카드 하나가 여러 풀에 속할 수 있으므로 「전멸과 80% 를 한 묶음으로」도, 「30% 에만
 * 나오는 카드」도 만들 수 있다.
 *
 * <p>지금은 <b>자리 여섯에 풀 여섯</b>이다. 두 자리가 한 풀을 나눠 쓰던 때가 있었는데
 * ({@code POOL_MIDDLE}·{@code POOL_LATE}), 그러면 앞 자리에서 뽑힌 카드가 뒤 자리의 풀에서도
 * 빠져 <b>자리 하나를 고치면 다른 자리가 같이 움직인다.</b> 자리마다 제 풀을 들면 그 얽힘이 없다.
 */
public final class TrialCatalog {

	/**
	 * 자리가 터지고 룰렛이 열리기까지 기다리는 틱. 15초다.
	 *
	 * <p>지금 이 값을 쓰는 자리는 {@link Trigger#ENTRY} 하나뿐이다. 다른 차원에서 순간이동해
	 * 떨어진 직후라 <b>어디에 왔는지도 모르는 상태</b>이고, 거기에 화면을 겹치면 룰렛이 무엇
	 * 때문에 떴는지 읽히지 않는다. 떨어진 것을 먼저 겪게 하고 잠깐 뒤에 뽑는다.
	 */
	public static final int DELAY_SETTLE_TICKS = 300;

	/**
	 * 기다리지 않는다. 자리가 터진 <b>그 틱</b>에 룰렛이 열린다.
	 *
	 * <p>팀이 <b>스스로 만든 결과</b>인 자리에 쓴다 — 크리스탈을 깼거나 드래곤 체력을 깎았거나다.
	 * 원인이 이미 화면 한가운데에 있었으므로 「무엇 때문에 떴는지」를 물을 필요가 없고, 여기서
	 * 15초를 기다리면 읽을 시간이 생기는 것이 아니라 <b>「해냈는데 왜 아무 일도 없지」</b>가 된다.
	 */
	public static final int DELAY_NONE = 0;

	/**
	 * 그 자리의 카드가 <b>어떻게 뜨는가.</b>
	 *
	 * <p>처음에는 여섯 자리가 전부 룰렛이었다. 그런데 「판을 멈추고 이름이 돈다」는 <b>그 자체로
	 * 무게가 있는 연출</b>이라, 자리마다 카드가 한 장뿐이거나 이미 다른 것이 화면을 채우고 있으면
	 * 멈춤이 값어치보다 비싸진다. 그래서 무엇을 멈추고 무엇을 보여 줄지를 자리에 적는다.
	 */
	public enum Reveal {
		/** 판을 멈추고 이름이 돌다 멈춘다. 멈춘 것이 그 판의 시련이다. */
		ROULETTE,
		/**
		 * 판은 멈추지만 룰렛은 돌지 않는다. 정해진 카드의 이름과 설명만 보여 준다.
		 *
		 * <p>카드가 한 장뿐인 자리에 쓴다. 한 장짜리 풀에서 이름이 도는 것은 결과가 정해진 굴림을
		 * 보여 주는 것이라 연출이 거짓말이 된다.
		 */
		FIXED_SCREEN,
		/** 아무것도 멈추지 않는다. 화면도 뜨지 않는다. */
		SILENT
	}

	/**
	 * 시련이 나오는 자리.
	 *
	 * <p>순서는 대체로 이 차례가 된다 — 크리스탈이 살아 있으면 드래곤 체력이 잘 안 깎이므로
	 * 크리스탈 쪽이 먼저 온다. 다만 순서를 코드가 강제하지는 않는다.
	 *
	 * <h2>지연은 자리의 성질이라 여기에 든다</h2>
	 *
	 * <p>전에는 관리자 쪽 상수 하나가 여섯 자리 전부에 똑같이 걸려 있었다. 그런데 기다리는 이유는
	 * <b>「무엇 때문에 떴는지 읽히게 한다」</b> 하나뿐이고, 그것이 필요한지 아닌지는 <b>자리마다
	 * 다르다.</b> 그래서 지연을 자리 옆에 값으로 적는다.
	 *
	 * <p>생성자가 인자로 요구하므로 <b>자리를 새로 만드는 사람은 지연을 정하지 않고는 만들 수
	 * 없다.</b> 상수 하나로 두면 새 자리가 조용히 15초를 물려받는데, 그 15초가 맞는지는 아무도
	 * 다시 묻지 않게 된다.
	 *
	 * <p>지금은 {@link #ENTRY} 하나만 0 이 아니다. 그래도 <b>{@code boolean} 하나로 줄이지 말
	 * 것</b> — 값이 자리에 붙어 있어야 어느 한 자리만 다르게 할 때 여기를 다시 뜯지 않는다.
	 *
	 * <h2>「어떻게 뜨는가」도 같은 이유로 여기에 든다</h2>
	 *
	 * <p>{@link Reveal} 도 생성자가 인자로 요구한다. 지연과 똑같은 판단이다 — 상수 하나나 기본값을
	 * 두면 <b>새 자리가 조용히 룰렛을 물려받고</b>, 그 자리에 룰렛이 맞는지는 아무도 다시 묻지
	 * 않는다. 정하지 않고는 자리를 만들 수 없게 둔다.
	 */
	public enum Trigger {
		/**
		 * 엔드에 들어서는 순간. <b>기다리는 자리는 이것뿐이다.</b>
		 *
		 * <p>다른 차원에서 끌려와 떨어진 직후라 어디에 왔는지도 모른다. 그 위에 화면을 겹치면
		 * 룰렛이 무엇 때문에 떴는지 읽히지 않는다.
		 */
		ENTRY("입장", DELAY_SETTLE_TICKS, Reveal.ROULETTE),
		/**
		 * 엔드 크리스탈이 처음 깨졌을 때.
		 *
		 * <p>자기가 조준하고 자기가 터뜨린 것이라 원인을 물을 필요가 없다. 곧바로 연다.
		 */
		FIRST_CRYSTAL("첫 크리스탈", DELAY_NONE, Reveal.ROULETTE),
		/** 엔드 크리스탈이 모두 깨졌을 때. 첫 크리스탈과 같은 이유로 곧바로 연다. */
		ALL_CRYSTALS("크리스탈 전멸", DELAY_NONE, Reveal.ROULETTE),
		/**
		 * 드래곤 체력이 80% 아래로 처음 내려갔을 때.
		 *
		 * <p>체력 문턱 셋도 <b>팀이 깎아서 만든 자리</b>다. 체력 막대가 눈앞에서 내려가는 것을
		 * 보며 때리던 중이라 원인이 분명하다. 곧바로 연다.
		 *
		 * <p>카드가 <b>한 장</b>뿐이라 룰렛을 돌리지 않는다. 판은 멈추고 그 카드의 이름과 설명만
		 * 보여 준다.
		 */
		HEALTH_80("체력 80%", DELAY_NONE, Reveal.FIXED_SCREEN),
		HEALTH_50("체력 50%", DELAY_NONE, Reveal.ROULETTE),
		/**
		 * 드래곤 체력이 30% 아래로 처음 내려갔을 때 — <b>최후의 저항.</b>
		 *
		 * <p>아무것도 멈추지 않고 화면도 띄우지 않는다. 이 자리는 굉음·화면 흔들림·엔더맨 소멸·
		 * 보스바 이름 변경이 <b>이미 「판이 바뀌었다」를 말한다.</b> 거기에 정지 화면을 얹으면
		 * 멈춤이 두 번 겹친다.
		 */
		HEALTH_30("체력 30%", DELAY_NONE, Reveal.SILENT);

		private final String label;
		private final int delayTicks;
		private final Reveal reveal;

		Trigger(String label, int delayTicks, Reveal reveal) {
			this.label = label;
			this.delayTicks = delayTicks;
			this.reveal = reveal;
		}

		public String label() {
			return label;
		}

		/**
		 * 이 자리가 터지고 룰렛이 열리기까지 기다리는 틱.
		 *
		 * <p>0 이면 <b>터진 그 틱</b>에 열린다 — 한 틱도 밀리지 않는다.
		 */
		public int delayTicks() {
			return delayTicks;
		}

		/** 이 자리의 카드가 어떻게 뜨는가 — 룰렛인가, 정해진 카드의 화면인가, 아무것도 없는가. */
		public Reveal reveal() {
			return reveal;
		}
	}

	/**
	 * 자리 여섯의 풀 여섯. 카드를 적을 때 이 이름으로 적는다.
	 *
	 * <p><b>풀을 나눠 쓰지 않는다.</b> 두 자리가 한 풀을 가리키면 앞 자리에서 뽑힌 카드가 뒤
	 * 자리의 풀에서도 빠지므로, 한 자리의 카드를 고치는 일이 다른 자리를 같이 움직인다. 자리를
	 * 새로 만드는 사람은 여기에 풀을 하나 더 적을 것.
	 */
	public static final Set<Trigger> POOL_ENTRY = EnumSet.of(Trigger.ENTRY);
	public static final Set<Trigger> POOL_FIRST_CRYSTAL = EnumSet.of(Trigger.FIRST_CRYSTAL);
	public static final Set<Trigger> POOL_ALL_CRYSTALS = EnumSet.of(Trigger.ALL_CRYSTALS);
	/** 카드가 <b>한 장</b>뿐인 자리다({@link Reveal#FIXED_SCREEN}). 늘리려면 자리의 연출부터 볼 것. */
	public static final Set<Trigger> POOL_HEALTH_80 = EnumSet.of(Trigger.HEALTH_80);
	public static final Set<Trigger> POOL_HEALTH_50 = EnumSet.of(Trigger.HEALTH_50);
	/**
	 * 최후의 저항 — <b>지금은 비어 있다.</b>
	 *
	 * <p>별개 보스전이라 따로 만든다. 빈 풀은 정상이고 그 자리는 그냥 지나간다
	 * ({@link #offerable}).
	 */
	public static final Set<Trigger> POOL_HEALTH_30 = EnumSet.of(Trigger.HEALTH_30);

	/**
	 * 위험 하나.
	 *
	 * <h2>왜 봉인 인터페이스인가</h2>
	 *
	 * <p>타입마다 필요한 값이 다르다. 칸을 하나로 합치면 낙뢰 카드에 「부활 개수」가, 부활
	 * 카드에 「예고 시간」이 붙어 <b>그 칸이 무슨 뜻인지 아무도 모르게 된다.</b>
	 *
	 * <p>실행기의 분기를 {@code default} 없는 switch 식으로 쓴다. 여기에 타입을 더하고 실행을
	 * 안 붙이면 <b>컴파일이 거절한다.</b> 이 저장소가 {@code GameOverCountdown.Reason} 에서
	 * 이미 쓰는 방식이다.
	 */
	public sealed interface Risk {

		/** 노리는 곳. */
		enum Aim {
			/** 그 사람이 조금 전 있던 자리. 계속 움직이면 빗나간다. */
			TRAIL,
			/**
			 * 아레나 안의 아무 곳. 서 있던 자리와 무관하다.
			 *
			 * <p>지점끼리는 <b>반경의 두 배보다 멀다</b>({@code TrialRisks} 가 강제한다). 겹침
			 * 구역이 생기면 한 사람이 한 틱에 두 번 맞아 즉사할 수 있기 때문이다.
			 */
			RANDOM_SPOT
		}

		/**
		 * 무엇이 떨어지는가.
		 *
		 * <h2>왜 값으로 가르는가</h2>
		 *
		 * <p>연출이 갈리지 않아 「낙뢰」가 <b>번개 대신 폭발 파티클과 폭발음</b>으로 나갔다. 카드
		 * 이름과 실제로 보이는 것이 달랐고, 사람은 TNT 가 터지는 줄 알았다. 실행기가 카드 id 를
		 * 보고 연출을 고르게 만들면 카드를 늘릴 때마다 같은 실수가 되풀이되므로 값으로 적는다.
		 */
		enum Impact {
			/** 그 자리가 터진다. 폭발 파티클과 폭발음. */
			EXPLOSION,
			/** 진짜 번개가 내리친다. */
			LIGHTNING
		}

		/**
		 * 예고한 자리에 무언가 떨어진다.
		 *
		 * <h2>피해 × 겹칠 수 있는 개수가 팀 체력을 넘으면 안 된다</h2>
		 *
		 * <p>이 전투의 설계 원칙은 <b>「즉사 메커닉 0개」</b>다. 팀 공유 체력은 20 이고 전멸은 곧
		 * 월드 삭제다. 그런데 이 레코드는 <b>{@code damage} 와 {@code count} 가 따로 적히므로</b>
		 * 한쪽만 올린 사람이 즉사 카드를 만들 수 있다 — {@code count} 가 여럿이면 한 사람이 여러
		 * 고리에 동시에 들 수 있기 때문이다.
		 *
		 * <p>{@link Aim#RANDOM_SPOT} 은 {@code TrialRisks} 가 <b>지점끼리 반경의 두 배보다 멀게</b>
		 * 강제해 겹침 구역을 없앤다. 그래서 {@code count} 를 열로 올려도 한 사람이 받는 것은 한 발
		 * 뿐이다. <b>그 규칙을 지우면 여기 적힌 값들이 그 순간 즉사가 된다.</b>
		 * {@code TrialRisksTest} 가 「가장 나쁜 경우의 한 틱 피해」를 카드 값에서 직접 계산해
		 * 20 미만인지 본다 — 값을 올리는 사람은 거기서 멈춘다.
		 *
		 * @param aim      발자국을 노리는가 아무 곳인가
		 * @param impact   떨어진 자리에서 무엇이 보이고 들리는가. 실행기가 이 값으로 갈린다
		 * @param interval 떨어지는 간격(틱)
		 * @param lookback 몇 틱 전 발자국을 노리는가. {@link Aim#RANDOM_SPOT} 이면 쓰지 않는다
		 * @param damage   반경 안에 남아 있는 사람이 받는 피해
		 * @param radius   피해 반경(블록). 바닥 고리도 이 크기로 그린다
		 * @param launch   맞은 사람을 띄우는 높이(블록). 0 이면 띄우지 않는다.
		 *                 띄우는 동안은 낙하 피해를 면제한다 — 이 카드의 위험은 노출이지 낙사가 아니다
		 * @param count    한 번에 몇 군데인가. {@link Aim#TRAIL} 이면 <b>무작위로 뽑는 사람 수</b>다.
		 *                 {@link Aim#RANDOM_SPOT} 은 최소 간격을 지킬 자리를 못 찾으면 적힌 것보다
		 *                 적게 나올 수 있다 — 겹치느니 한 발 빠지는 쪽이다
		 */
		record DelayedStrike(Aim aim, Impact impact, int interval, int lookback, float damage,
				double radius, double launch, int count) implements Risk {
		}

		/**
		 * 흑요석 기둥에서 날아오는 불덩이.
		 *
		 * <h2>왜 바닐라 화염구를 쓰지 않는가</h2>
		 *
		 * <p>{@code LargeFireball} 의 블록 파괴는 {@code mobGriefing} 게임룰에 묶여 있어 우리가
		 * 끌 수 없다. 끄면 크리퍼까지 함께 꺼진다. 불도 폭발에 딸려 붙는다. 엔드 섬에 구멍이
		 * 뚫리면 다음 전투부터 발판이 달라지므로 <b>궤적을 파티클로 그리고 착탄을 직접 처리</b>한다 —
		 * 보이는 것은 같고 값은 전부 우리 것이다.
		 *
		 * @param interval   발사 간격(틱)
		 * @param traceTicks 궤적을 보여 주는 시간. 이만큼 날아온다
		 * @param damage     착탄 반경 안의 사람이 받는 피해
		 * @param radius     착탄 반경(블록)
		 * @param count      한 번에 몇 발. 사람마다 한 발씩 노린다
		 */
		record TracedProjectile(int interval, int traceTicks, float damage, double radius,
				int count) implements Risk {
		}

		/**
		 * 크리스탈을 깨기 어렵게 만든다.
		 *
		 * <p>피해를 주지 않고 <b>시간을 빼앗는</b> 축이다. 보상이 없는 이상 한 풀의 카드가 전부
		 * 「맞으면 아프다」면 룰렛이 무엇을 뽑든 같은 판이 된다.
		 *
		 * @param arrowImmune  참이면 크리스탈이 투사체에 맞지 않는다. 올라가서 깨야 한다
		 * @param restoreCage  참이면 모든 크리스탈에 쇠창살이 다시 생긴다. 없던 탑에도 생긴다
		 * @param digSlowdown  참이면 팀 전원에게 채굴 피로 I. <b>등급은 0 고정이다</b> —
		 *                     26.3 이 등급별 공식을 바꿨는데 등급 0 만 두 판이 같다
		 */
		record CrystalGuard(boolean arrowImmune, boolean restoreCage, boolean digSlowdown)
				implements Risk {
		}

		/** 드래곤이 누구를 노리는가. */
		enum Focus {
			/** 무작위 한 명. 주기마다 다시 고른다. */
			RANDOM,
			/** 크리스탈을 가장 최근에 깬 사람. 깬 적이 없으면 무작위로 물러난다. */
			CRYSTAL_BREAKER
		}

		/**
		 * 드래곤의 공격이 한 사람에게 쏠린다.
		 *
		 * <h2>페이즈를 건드리지 않는다 — 한 번 크게 틀린 자리다</h2>
		 *
		 * <p>처음에는 드래곤을 <b>돌진 페이즈로 밀어넣어</b> 만들었다. 실제로 플레이하니 두 가지가
		 * 드러났다. 하나는 「타겟팅만 뜨고 아무것도 안 온다」(바닐라가 이미 같은 사람을 노리고 있어
		 * 우리 개입이 무효였다), 다른 하나가 더 나빴다 — <b>드래곤이 착지를 아예 하지 않게 됐다.</b>
		 *
		 * <p>바닐라는 드래곤이 원을 <b>한 바퀴 다 돌아 경로가 끝난 틱</b>에만 「착지할까」를 굴린다.
		 * 우리가 주기적으로 끼어들면 돌아올 때마다 경로가 처음부터 다시 깔려 그 틱이 영영 오지
		 * 않는다. 시간 비율의 문제가 아니라 <b>주기를 끊는 것</b>이 문제였다.
		 *
		 * <p>그래서 이제 <b>우리가 직접 구체를 날린다.</b> 페이즈를 한 번도 만지지 않으므로 착지도
		 * 크리스탈도 바닐라 그대로다. <b>이 판단을 되돌리지 말 것.</b>
		 *
		 * @param focus     대상을 고르는 법
		 * @param markTicks 한 사람을 노리는 시간. 이 동안 {@code shots} 발이 고르게 나간다
		 * @param restTicks 그다음 쉬는 시간. 쉬는 틈이 없으면 다른 카드와 겹칠 때 버거워진다
		 * @param shots     {@code markTicks} 동안 날리는 구체 수
		 * @param damage    구체 한 발의 피해. <b>전부 맞으면 {@code shots × damage}</b> 라
		 *                  팀 체력 20 을 쉽게 넘긴다 — 발 사이가 벌어져 있어 도망칠 수 있다는 전제다
		 */
		record DragonFocus(Focus focus, int markTicks, int restTicks, int shots, float damage)
				implements Risk {
		}

		/**
		 * 크리스탈이 되살아난다.
		 *
		 * <p>연출이 도는 동안 드래곤은 중앙 상공에 머문다 — <b>때릴 수 없는 시간이 곧 대가</b>다.
		 *
		 * @param count     되살릴 개수. 남은 자리가 그보다 적으면 있는 만큼만
		 * @param showTicks 복구 연출 길이. 이 동안 드래곤이 중앙에 머문다
		 * @param heal      되살아난 크리스탈 하나당 드래곤이 회복하는 양
		 */
		record CrystalRevive(int count, int showTicks, float heal) implements Risk {
		}

		/**
		 * 중앙에서 바닥 고리가 퍼져 나간다.
		 *
		 * <p>피해가 없다. 이 카드가 빼앗는 것은 체력이 아니라 <b>발</b>이다 — 고리가 지나가는 순간
		 * 바닥을 딛고 있으면 잠시 못 움직인다. 점프하면 통과하므로 요구하는 행동이 하나뿐이다.
		 *
		 * @param interval    고리가 새로 퍼지기 시작하는 간격(틱)
		 * @param travelTicks 중앙에서 {@code maxRadius} 까지 퍼지는 데 걸리는 틱
		 * @param maxRadius   고리가 닿는 마지막 반경(블록)
		 * @param rootTicks   고리에 걸린 사람이 묶이는 시간(틱)
		 */
		record EnderPulse(int interval, int travelTicks, double maxRadius, int rootTicks)
				implements Risk {
		}

		/**
		 * 크리스탈 하나를 깨면 다른 하나가 잠시 보호막을 두른다.
		 *
		 * <p>주기가 없다. 발동을 정하는 것은 시간이 아니라 <b>팀의 행동</b>이다.
		 *
		 * @param shieldTicks 보호막이 남아 있는 시간(틱)
		 */
		record CrystalLink(int shieldTicks) implements Risk {
		}

		/**
		 * 크리스탈 하나가 달아오르고, 제때 못 부수면 빔이 한 사람을 문다.
		 *
		 * @param fuseTicks       달아오른 크리스탈을 부술 수 있는 시간(틱). 넘기면 빔이 시작된다
		 * @param beamTicks       빔이 붙어 있는 시간(틱)
		 * @param restTicks       빔이 끝나거나 제때 부순 뒤 다음 크리스탈까지 쉬는 시간(틱)
		 * @param damagePerSecond 빔에 물린 사람이 <b>1초마다</b> 받는 피해
		 */
		record CrystalOvercharge(int fuseTicks, int beamTicks, int restTicks, float damagePerSecond)
				implements Risk {
		}

		/**
		 * 가장자리에서 중앙으로 소용돌이가 밀려온다.
		 *
		 * <p>{@code knockback} 의 <b>방향</b>은 값이 정하지 않는다 — 안쪽(폭풍 진행 방향)으로만
		 * 민다. 엔드 중앙 섬은 사방이 허공이고 공유 체력이라 한 사람의 낙사가 팀 전체를 끝낸다.
		 *
		 * @param speedPerSecond 소용돌이가 1초에 나아가는 거리(블록)
		 * @param count          한 번에 도는 소용돌이 수
		 * @param damage         닿은 사람이 받는 피해
		 * @param knockback      미는 세기. 방향은 실행기가 정한다
		 * @param restTicks      소용돌이들이 중앙에 닿은 뒤 새로 시작하기까지 쉬는 시간(틱)
		 */
		record EnderStorm(int count, double speedPerSecond, float damage, double knockback,
				int restTicks) implements Risk {
		}

		/**
		 * 엔드가 마른다 — 물과 용암이 사라지고 다시 놓을 수 없게 된다.
		 *
		 * <p><b>칸이 하나도 없다.</b> 한 번 터지고 끝나는 카드인데 「얼마나」를 정할 자리가 없기
		 * 때문이다. 다만 터지고 사라지지는 않는다 — 다시 설치하지 못하는 금지가 전투가 끝날
		 * 때까지 남는다.
		 */
		record DryWorld() implements Risk {
		}

		/**
		 * 엔드의 엔더맨이 한꺼번에 적대가 된다.
		 *
		 * @param hostileTicks 적대인 시간(틱). 지나면 원래대로 돌아간다
		 */
		record NightHost(int hostileTicks) implements Risk {
		}

		/**
		 * 정해진 시간 동안 아레나 곳곳에 표시가 떴다가 착탄한다.
		 *
		 * <p>지점은 {@code TrialRisks} 의 <b>겹침 금지</b> 목록에 올린다. 한 볼리 안에서만이 아니라
		 * <b>그때 살아 있는 다른 카드의 지점과도</b> 겹치면 안 된다 — 「낙뢰」와 이 카드가 동시에
		 * 돌 수 있고, 겹친 자리는 한 틱에 두 발이다.
		 *
		 * @param durationTicks 이 카드가 비를 내리는 전체 시간(틱). 한 번 쓰고 끝난다
		 * @param minInterval   다음 볼리까지의 가장 짧은 간격(틱)
		 * @param maxInterval   다음 볼리까지의 가장 긴 간격(틱)
		 * @param minSpots      한 볼리의 가장 적은 지점 수
		 * @param maxSpots      한 볼리의 가장 많은 지점 수
		 * @param warnTicks     표시가 뜨고 착탄까지의 시간(틱)
		 * @param damage        반경 안에 남아 있는 사람이 받는 피해
		 * @param radius        피해 반경(블록). 바닥 표시도 이 크기다
		 */
		record EndRain(int durationTicks, int minInterval, int maxInterval, int minSpots,
				int maxSpots, int warnTicks, float damage, double radius) implements Risk {
		}

		/**
		 * 드래곤이 착지할 때마다 중앙에서 충격이 퍼진다.
		 *
		 * <p>주기가 없다. 발동을 정하는 것은 시간이 아니라 <b>드래곤의 착지</b>다.
		 *
		 * @param travelTicks 중앙에서 {@code maxRadius} 까지 퍼지는 데 걸리는 틱
		 * @param maxRadius   고리가 닿는 마지막 반경(블록)
		 * @param damage      고리가 지나갈 때 바닥을 딛고 있던 사람이 받는 피해
		 * @param knockback   바깥으로 미는 거리(블록). <b>섬 밖으로 밀면 안 된다</b> —
		 *                    근거는 {@code TrialLandingShock} 에 적어 두었다
		 */
		record LandingShock(int travelTicks, double maxRadius, float damage, double knockback)
				implements Risk {
		}

		/**
		 * 핫바의 몇 칸이 굳는다.
		 *
		 * <p>쌓이지 않는다. 주기마다 굳는 칸이 <b>옮겨 다닐 뿐</b> 개수는 늘 {@code slots} 다.
		 *
		 * @param interval 굳는 칸이 옮겨 가는 간격(틱)
		 * @param slots    한 번에 굳는 칸 수
		 */
		record HotbarLock(int interval, int slots) implements Risk {
		}
	}

	/**
	 * 카드 한 장.
	 *
	 * @param id          저장에 쓰는 식별자
	 * @param name        룰렛과 화면에 뜨는 이름
	 * @param description 무엇이 일어나는지. 뽑힌 순간 읽는 글이다
	 * @param pools       이 카드가 나올 수 있는 자리들
	 * @param risks       이 카드가 거는 위험들. 여럿이면 함께 돈다
	 */
	public record Trial(String id, String name, String description, Set<Trigger> pools,
			List<Risk> risks) {

		/** 위험이 하나뿐인 흔한 경우. */
		public Trial(String id, String name, String description, Set<Trigger> pools, Risk risk) {
			this(id, name, description, pools, List.of(risk));
		}
	}

	/**
	 * 카드 목록. 내용은 {@code docs/드래곤-시련-카드.md} 와 짝이다.
	 *
	 * <p><b>빈 풀은 정상이다.</b> 카드를 채워 가는 동안 자리가 비면 그 자리는 그냥 지나간다.
	 */
	private static final List<Trial> TRIALS = List.of(
			new Trial("sharedfate:ground_strike", "자리 폭격",
					"12초마다 한 사람이 2초 전에 있던 자리가 터집니다. 맞으면 하늘로 떠오릅니다.",
					POOL_ENTRY,
					// 피해 18 은 이 카드의 천장에 가깝다. 팀 공유 체력이 20 이라 가득 찬 상태에서
					// 한 대 맞으면 하트 1칸만 남는다 — 「즉사 메커닉 0개」는 지키지만(한 대로는
					// 죽지 않는다) 다른 위험과 겹치면 죽는다. 여기서 조금만 더 올리면 그 순간
					// 즉사 카드가 되므로, 다음에 이 값을 만질 사람은 20 을 넘기지 말 것.
					new Risk.DelayedStrike(Risk.Aim.TRAIL, Risk.Impact.EXPLOSION,
							240, 40, 18.0F, 2.0, 4.0, 1)),
			new Trial("sharedfate:pillar_fireball", "기둥 화염구",
					"10초마다 가장 가까운 흑요석 기둥에서 불덩이가 날아옵니다. 2초 동안 궤적이 보입니다.",
					POOL_ENTRY,
					// 실제로 맞아 보고 정한 값이다. 궤적 100틱(5초)은 「걸어서 비키면 되는」 속도라
					// 위협이 아니었고 피해 6 은 「아예 안 아픈」 값이었다. 궤적을 40틱으로 줄여
					// 속도를 2.5배로, 반경을 45% 넓히고, 피해를 14 로 올렸다.
					//
					// 궤적 40틱이 예고의 전부다. 이 카드가 요구하는 행동은 「제자리에서 옆으로
					// 비키기」뿐이므로 기준은 TrialWarning.TICKS_SIDESTEP(30틱)이고 40 은 그보다
					// 길다. 여기를 30 아래로 내리면 예고가 사후 통보가 된다.
					new Risk.TracedProjectile(200, 40, 14.0F, 4.35, 1)),
			new Trial("sharedfate:lightning_storm", "낙뢰",
					"6초마다 아레나 열 곳에 번개가 떨어집니다. 떨어지기 전에 자리가 보입니다.",
					POOL_ALL_CRYSTALS,
					// 실제로 맞아 보고 정한 값이다. 두 곳은 「비켜야 할 이유」가 거의 없었고 피해 5 는
					// 약했다. 열 곳으로 늘리고 피해를 12 로 올렸는데, 그러고도 「아직 안 아픈 정도」라
					// 하여 18 로 올렸다.
					//
					// 18 이 이 카드의 천장이다. 팀 공유 체력이 20 이라 가득 찬 상태에서 한 대 맞으면
					// 하트 1칸이 남는다 — 「자리 폭격」과 같은 값이고 같은 근거다. 여기서 더 올리면
					// 그 순간 즉사 카드가 된다.
					//
					// 열 개짜리인데 18 을 쓸 수 있는 것은 겹침 규칙 하나 덕이다. TrialRisks 가
					// RANDOM_SPOT 전체에 「반경의 두 배보다 멀리」를 강제해 한 사람이 한 볼리에 한
					// 발만 맞게 만든다. 그 규칙을 지우면 두 발이 겹쳐 36 이 되고, 이 값은 그날로
					// 즉사 카드다.
					new Risk.DelayedStrike(Risk.Aim.RANDOM_SPOT, Risk.Impact.LIGHTNING,
							120, 0, 18.0F, 3.0, 0.0, 10)),
			new Trial("sharedfate:crystal_ward", "크리스탈 보호막",
					"남은 크리스탈이 화살에 맞지 않습니다. 올라가서 깨야 합니다.",
					POOL_FIRST_CRYSTAL,
					new Risk.CrystalGuard(true, false, false)),
			new Trial("sharedfate:iron_cage", "쇠창살과 무딘 곡괭이",
					"모든 크리스탈에 쇠창살이 다시 생기고 팀 전원이 채굴 피로에 걸립니다.",
					POOL_FIRST_CRYSTAL,
					new Risk.CrystalGuard(false, true, true)),
			new Trial("sharedfate:dragon_mark", "표적",
					"크리스탈을 깬 사람이 10초 동안 표적이 되고 드래곤이 구체를 5발 날립니다. 그다음 20초는 쉽니다.",
					POOL_FIRST_CRYSTAL,
					// 10초 표적 · 20초 휴식 · 5발 · 발당 6. 다 맞으면 30 이라 팀 체력 20 을 넘지만
					// 발 사이가 2초씩 벌어져 있고 착탄 자리가 발사 시점에 얼어붙으므로, 전부 맞는
					// 것은 「가만히 서 있었다」는 뜻이다. 한 틱에 들어오는 것은 언제나 한 발 6 이다 —
					// 구체가 날아가는 시간이 발사 간격을 넘지 않아 두 발이 같은 틱에 닿지 않는다.
					new Risk.DragonFocus(Risk.Focus.CRYSTAL_BREAKER, 200, 400, 5, 6.0F)),
			new Trial("sharedfate:crystal_revival", "부활",
					"모든 크리스탈이 되살아납니다. 드래곤이 중앙 상공에서 그것을 지켜봅니다.",
					POOL_HEALTH_50,
					new Risk.CrystalRevive(10, 100, 0.0F)),
			new Trial("sharedfate:ender_pulse", "엔더 파동",
					"20초마다 중앙에서 바닥 고리가 퍼집니다. 지나갈 때 땅을 딛고 있으면 잠시 묶입니다.",
					POOL_ENTRY,
					new Risk.EnderPulse(400, 80, 42.0, 60)),
			new Trial("sharedfate:hotbar_lock", "굳는 손",
					"30초마다 핫바 두 칸이 굳습니다. 굳은 칸의 물건은 휘두를 수도 쓸 수도 없습니다.",
					POOL_ENTRY,
					new Risk.HotbarLock(600, 2)),
			new Trial("sharedfate:crystal_link", "연결된 수정",
					"크리스탈을 부수면 가장 가까운 다른 크리스탈이 8초 동안 보호막을 두릅니다.",
					POOL_FIRST_CRYSTAL,
					new Risk.CrystalLink(160)),
			new Trial("sharedfate:crystal_overcharge", "수정 과충전",
					"크리스탈 하나가 붉게 달아오릅니다. 15초 안에 부수지 못하면 한 명이 10초 동안 빔에 물립니다.",
					POOL_FIRST_CRYSTAL,
					new Risk.CrystalOvercharge(300, 200, 400, 1.0F)),
			new Trial("sharedfate:ender_storm", "엔더폭풍",
					"가장자리에서 중앙으로 소용돌이 둘이 밀려옵니다. 닿으면 아프고 안쪽으로 밀려납니다.",
					POOL_ALL_CRYSTALS,
					// 넉백 세기는 실행기가 정한다. 여기 적힌 1.0 은 「민다」는 사실을 값으로 남긴
					// 것이고, 방향은 값이 아니라 규칙이다 — 반드시 안쪽이다.
					new Risk.EnderStorm(2, 2.0, 2.0F, 1.0, 500)),
			new Trial("sharedfate:dry_world", "메마른 세계",
					"엔드의 물과 용암이 증발하고, 그 뒤로는 물·용암·서리눈을 놓을 수 없습니다.",
					POOL_ALL_CRYSTALS,
					new Risk.DryWorld()),
			new Trial("sharedfate:night_host", "밤의 군세",
					"엔드의 엔더맨 전원이 20초 동안 적대가 되어 쳐다보지 않아도 달려옵니다.",
					POOL_HEALTH_50,
					new Risk.NightHost(400)),
			new Trial("sharedfate:end_rain", "종말의 비",
					"30초 동안 아레나 곳곳에 표시가 떴다가 떨어집니다. 표시된 자리에 서 있으면 크게 다칩니다.",
					POOL_HEALTH_50,
					new Risk.EndRain(600, 40, 60, 10, 15, 30, 10.0F, 2.5)),
			new Trial("sharedfate:landing_shock", "착지 충격",
					"드래곤이 착지할 때마다 중앙에서 충격이 퍼집니다. 땅을 딛고 있으면 맞고 밀려납니다.",
					POOL_HEALTH_80,
					// 궤적 40 은 실행기가 정할 값이다. 넉백 8 의 근거는 TrialLandingShock 에 있다.
					new Risk.LandingShock(40, 15.0, 4.0F, 8.0)));

	private TrialCatalog() {
	}

	public static List<Trial> all() {
		return TRIALS;
	}

	public static @Nullable Trial byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (Trial trial : TRIALS) {
			if (trial.id().equals(id)) {
				return trial;
			}
		}
		return null;
	}

	/**
	 * 이 자리에서 뽑힐 수 있는 카드들. 이미 뽑힌 것은 빠진다.
	 *
	 * <p>비어 있으면 그 트리거는 아무것도 주지 않고 지나간다 — 풀이 마른 것을 오류로 다루지
	 * 않는다. 카드를 채워 가는 동안에는 빈 풀이 정상이다.
	 */
	public static List<Trial> offerable(@Nullable Trigger trigger,
			@Nullable Collection<String> alreadyChosen) {
		List<Trial> available = new ArrayList<>();
		if (trigger == null) {
			return available;
		}
		for (Trial trial : TRIALS) {
			if (!trial.pools().contains(trigger)) {
				continue;
			}
			if (alreadyChosen == null || !alreadyChosen.contains(trial.id())) {
				available.add(trial);
			}
		}
		return available;
	}
}
