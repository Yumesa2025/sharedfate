package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 엔드 시련 룰렛.
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
 * @param triggerLabel 이 시련이 나온 자리의 이름 (예: 「첫 크리스탈」)
 * @param resultIndex  {@link #options} 에서 멈출 칸. 범위를 벗어나면 화면이 0 으로 본다
 * @param spinTicks    연출 길이(틱). 0 이하면 돌리지 않고 결과만 보여 준다
 * @param options      룰렛에 오를 카드들. 한 장뿐일 수도 있다 — 그것도 정직한 연출이다
 */
public record TrialRoulettePayload(String triggerLabel, int resultIndex, int spinTicks,
		List<TrialOption> options) implements CustomPacketPayload {

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
					TrialOption.CODEC.apply(ByteBufCodecs.list(MAX_OPTIONS)),
					TrialRoulettePayload::options,
					TrialRoulettePayload::new);

	public TrialRoulettePayload {
		triggerLabel = triggerLabel == null ? "" : triggerLabel;
		options = options == null ? List.of() : List.copyOf(options);
		// VAR_INT 는 음수를 실을 수 없다. 서버 계산이 어긋나도 패킷이 터지면 안 된다.
		resultIndex = Math.max(0, resultIndex);
		spinTicks = Math.max(0, spinTicks);
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
