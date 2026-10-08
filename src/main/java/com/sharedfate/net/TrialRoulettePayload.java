package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 엔드 시련 화면.
 *
 * <h2>화면 하나로 두 연출을 그린다</h2>
 *
 * <p>자리마다 카드가 <b>어떻게 뜨는지</b>가 다르다({@code TrialCatalog.Reveal}). 룰렛이 도는
 * 자리가 있고, 판은 멈추되 <b>정해진 카드 한 장의 이름과 설명만</b> 보여 주는 자리가 있다 —
 * 카드가 한 장뿐인 풀에서 이름이 도는 것은 결과가 정해진 굴림을 보여 주는 것이라 연출이
 * 거짓말이 되기 때문이다.
 *
 * <p>그 둘을 <b>묶음 하나로 나른다.</b> 새 묶음을 만들면 화면 코드가 두 벌이 되고, 그러면
 * 「글자 배율을 낮춰 판 밖으로 안 나가게 하기」 같은 고침이 한쪽에만 들어간다. 가르는 값은
 * {@link #spinTicks} 하나다 — <b>0 이면 굴리지 않는다.</b> 굴림 길이를 0 으로 두는 것 말고
 * 「굴릴 것인가」를 따로 싣지 않는 이유는, 두 값이 어긋나면(굴림 0 인데 굴리라고 하면) 화면이
 * 무엇을 해야 할지 알 수 없는 상태가 생기기 때문이다.
 *
 * <p>{@code TrialCatalog.Reveal.SILENT} 은 이 묶음을 <b>아예 보내지 않는다.</b>
 *
 * <h2>왜 결과를 미리 보내는가</h2>
 *
 * <p>칸이 바뀔 때마다 패킷을 보내면 4초 동안 열다섯 번을 보내게 되고, 그중 하나만 늦어도 화면이
 * 멈췄다 튄다. <b>후보와 결과를 한 번에 보내고 연출은 클라이언트가 돌린다.</b> 서버는 결과를
 * 이미 정해 두었으므로(룰렛은 열기 전에 뽑는다) 늦게 도착해도 답이 달라지지 않는다.
 *
 * <h2>왜 설명까지 실어 보내는가</h2>
 *
 * <p>클라이언트는 시련 목록을 모른다 — 증강과 같다. 정의를 클라이언트에도 넣으면 서버와
 * 어긋난 판이 섞였을 때 <b>화면에 뜬 글과 실제로 걸리는 효과가 달라진다.</b> 서버가 이미 풀어서
 * 보내면 그 사고가 구조적으로 불가능하다.
 *
 * <h2>왜 읽는 시간까지 실어 보내는가</h2>
 *
 * <p>전에는 화면이 {@code TrialFreeze.HOLD_TICKS} 를 <b>직접 읽어</b> 썼다. 서버와 클라이언트가
 * 같은 jar 이라 값이 어긋날 수 없었고, 그것이 「한쪽만 바뀌면 화면이 먼저 닫혀 얼어 있는 채로
 * 서 있거나, 시간이 먼저 흘러 화면 뒤에서 드래곤이 움직인다」를 막는 장치였다.
 *
 * <p>그런데 <b>자리마다 멈추는 시간이 달라졌다</b> — 룰렛은 {@code TrialFreeze.HOLD_TICKS},
 * 정해진 카드 화면은 {@code TrialFreeze.FIXED_HOLD_TICKS} 다. 상수 하나를 읽어서는 둘을 맞출
 * 수 없고, 화면이 자리마다 골라 읽게 하면 <b>「어느 자리인가」를 화면도 알아야</b> 한다.
 * 그래서 고르는 일은 서버에 남기고 <b>고른 결과</b>({@link #holdTicks})를 싣는다. 이렇게 두면
 * 자리가 늘어도 화면은 한 줄도 안 바뀐다.
 *
 * @param triggerLabel 이 시련이 나온 자리의 이름 (예: 「첫 크리스탈」)
 * @param resultIndex  {@link #options} 에서 멈출 칸. 범위를 벗어나면 화면이 0 으로 본다
 * @param spinTicks    굴림 길이(틱). <b>0 이하면 한 틱도 굴리지 않고</b> 결과부터 보여 준다
 * @param holdTicks    결과를 붙잡아 두고 읽게 하는 시간(틱). 굴림이 끝난 뒤부터 센다.
 *                     <b>서버가 실제로 판을 얼려 두는 시간과 같아야 한다</b> — 아래 참고
 * @param options      화면에 오를 카드들. 한 장뿐일 수도 있다
 */
public record TrialRoulettePayload(String triggerLabel, int resultIndex, int spinTicks,
		int holdTicks, List<TrialOption> options) implements CustomPacketPayload {

	/**
	 * 룰렛에 올릴 수 있는 카드 수 상한.
	 *
	 * <p>풀 하나에 이보다 많은 카드가 들어가면 서버가 잘라 보낸다. 카드는 계속 늘어날 것이라
	 * 넉넉히 잡되, 코덱에 상한이 있어야 <b>악의적인 패킷이 메모리를 먹는 길</b>이 막힌다.
	 */
	public static final int MAX_OPTIONS = 16;

	/**
	 * 화면에 그릴 카드 하나.
	 *
	 * @param id          시련 식별자. 화면은 쓰지 않지만 로그와 시험이 짚을 때 쓴다
	 * @param name        룰렛이 돌며 바뀌는 글자
	 * @param description 멈춘 뒤 읽는 글. 무엇이 일어나는지
	 */
	public record TrialOption(String id, String name, String description) {

		public TrialOption {
			// 셋 중 하나라도 null 이면 패킷 인코딩이 통째로 터진다. 카드를 새로 적는 사람이
			// 설명을 빠뜨렸다고 전투가 끊기면 안 된다.
			id = id == null ? "" : id;
			name = name == null ? "" : name;
			description = description == null ? "" : description;
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, TrialOption> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, TrialOption::id,
						ByteBufCodecs.STRING_UTF8, TrialOption::name,
						ByteBufCodecs.STRING_UTF8, TrialOption::description,
						TrialOption::new);
	}

	public static final Type<TrialRoulettePayload> TYPE =
			new Type<>(SharedFateMod.id("trial_roulette"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialRoulettePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, TrialRoulettePayload::triggerLabel,
					ByteBufCodecs.VAR_INT, TrialRoulettePayload::resultIndex,
					ByteBufCodecs.VAR_INT, TrialRoulettePayload::spinTicks,
					ByteBufCodecs.VAR_INT, TrialRoulettePayload::holdTicks,
					TrialOption.CODEC.apply(ByteBufCodecs.list(MAX_OPTIONS)),
					TrialRoulettePayload::options,
					TrialRoulettePayload::new);

	public TrialRoulettePayload {
		triggerLabel = triggerLabel == null ? "" : triggerLabel;
		options = options == null ? List.of() : List.copyOf(options);
		// VAR_INT 는 음수를 실을 수 없다. 서버 계산이 어긋나도 패킷이 터지면 안 된다.
		resultIndex = Math.max(0, resultIndex);
		spinTicks = Math.max(0, spinTicks);
		// 0 으로 눌러 두기만 하고 최소값을 세우지 않는다. 화면이 서버가 얼려 두지 않는 시간을
		// 지어내면 얼음이 풀린 판에서 화면만 남아 드래곤에게 맞는다 — 짧게 번쩍이는 쪽이 낫다.
		holdTicks = Math.max(0, holdTicks);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** 멈출 카드. 번호가 어긋나면 첫 장으로 본다 — 빈 화면을 띄우는 것보다 낫다. */
	public TrialOption result() {
		if (options.isEmpty()) {
			return new TrialOption("", "", "");
		}
		return options.get(Math.min(resultIndex, options.size() - 1));
	}
}
