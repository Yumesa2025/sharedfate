package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 증강·세트 정의를 <b>어디서 읽는가</b>.
 *
 * <p>0.26.6-dev 까지는 {@code config/} 에 정의를 꺼내 놓고 그 파일만 읽었다. 판을 올려도
 * 덮어쓰지 않아 <b>판마다 사람이 손으로 두 파일을 지워야 했고</b>, 하나만 지우면 반쪽이 옛
 * 정의로 읽히는데 오류도 나지 않았다. 되풀이해서 당한 자리다.
 *
 * <p>0.26.7-dev 부터 정의는 모드 안에만 있다. 이 시험은 그 상태가 유지되는지 지킨다 —
 * 특히 <b>생산 경로가 다시 파일을 읽게 되는 것</b>을 막는다. 그 되돌림은 빌드도 로그도 통과하고
 * 「새 증강이 안 들어온다」는 증상으로만 나타나므로, 시험으로 못박지 않으면 알 길이 없다.
 */
class DefinitionSourceTest {

	// ------------------------------------------------- 번들이 실제로 읽히는가

	@Test
	void 증강은_모드_안의_정의에서_읽힌다() {
		PerkRegistry.loadBundled();
		assertEquals(96, PerkRegistry.all().size(),
				"모드 안의 기본 풀을 못 읽었다. config/ 없이도 증강이 있어야 한다");
	}

	@Test
	void 세트도_모드_안의_정의에서_읽힌다() {
		PerkSetRegistry.loadBundled();
		int types = 0;
		int tiers = 0;
		for (PerkSetType type : PerkSetType.values()) {
			List<PerkSets.Tier> list = PerkSetRegistry.tiersOf(type);
			if (!list.isEmpty()) {
				types++;
				tiers += list.size();
			}
		}
		// 「개척」이 들어와 유형 14 · 단계 28 이 됐다. 개척은 2단계 하나뿐이라 단계가 하나만
		// 는다 — 3·4단계를 안 만든 것은 의도한 것이다(PerkSetType.PIONEER 주석).
		assertEquals(14, types, "세트 유형");
		assertEquals(28, tiers, "세트 단계");
	}

	/**
	 * 증강과 세트는 <b>언제나 함께</b> 들어와야 한다.
	 *
	 * <p>예전 사고의 모양이 정확히 이것이었다 — 한쪽은 새 정의, 한쪽은 옛 정의. 둘을 한
	 * 시험에서 함께 보면 한쪽만 깨지는 것을 바로 알 수 있다.
	 */
	@Test
	void 증강과_세트가_한쪽만_비지_않는다() {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		assertFalse(PerkRegistry.all().isEmpty(), "증강이 비었다");
		assertFalse(PerkSetRegistry.tiersOf(PerkSetType.MINING).isEmpty(), "세트가 비었다");
	}

	// ------------------------------------------------- 파일을 만들지 않는가

	@Test
	void 증강을_읽어도_설정_폴더에_파일을_만들지_않는다(@TempDir Path dir) {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		assertFalse(Files.exists(dir.resolve(PerkRegistry.FILE_NAME)),
				"증강 정의 파일을 만들면 판을 올릴 때마다 손으로 지워야 하는 옛 사고가 돌아온다");
		assertFalse(Files.exists(dir.resolve(PerkSetRegistry.FILE_NAME)),
				"세트 정의 파일을 만들면 안 된다");
	}

	@Test
	void 시험_주입구도_파일이_없으면_만들지_않는다(@TempDir Path dir) {
		PerkRegistry.load(dir);
		PerkSetRegistry.load(dir);
		assertTrue(PerkRegistry.all().isEmpty(), "없는 파일을 읽었는데 증강이 생겼다");
		assertFalse(Files.exists(dir.resolve(PerkRegistry.FILE_NAME)),
				"파일이 없을 때 꺼내 놓으면 안 된다");
		assertFalse(Files.exists(dir.resolve(PerkSetRegistry.FILE_NAME)),
				"파일이 없을 때 꺼내 놓으면 안 된다");
	}

	// ------------------------------------------------- 옛 파일 알림

	@Test
	void 옛_정의_파일이_없으면_아무_말도_하지_않는다(@TempDir Path dir) {
		assertEquals(List.of(), LegacyDefinitionFiles.presentIn(dir));
	}

	@Test
	void 옛_정의_파일이_하나만_남아_있어도_알아본다(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkSetRegistry.FILE_NAME), "{}", StandardCharsets.UTF_8);
		assertEquals(List.of(PerkSetRegistry.FILE_NAME), LegacyDefinitionFiles.presentIn(dir));
	}

	@Test
	void 옛_정의_파일_둘을_모두_알아본다(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), "{}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(PerkSetRegistry.FILE_NAME), "{}", StandardCharsets.UTF_8);
		assertEquals(List.of(PerkRegistry.FILE_NAME, PerkSetRegistry.FILE_NAME),
				LegacyDefinitionFiles.presentIn(dir));
	}

	@Test
	void 설정_폴더가_없어도_터지지_않는다() {
		assertEquals(List.of(), LegacyDefinitionFiles.presentIn(null));
	}

	/** 알림은 읽지 않는 것이 요점이다 — <b>지우지는 않는다.</b> */
	@Test
	void 알림은_남의_파일을_지우지_않는다(@TempDir Path dir) throws IOException {
		Path perks = dir.resolve(PerkRegistry.FILE_NAME);
		Files.writeString(perks, "{}", StandardCharsets.UTF_8);
		LegacyDefinitionFiles.noticeIfPresent(dir);
		assertTrue(Files.exists(perks), "읽지 않을 뿐이지 지울 이유는 없다");
	}

	// ------------------------------------------------- 되돌림 방지

	/**
	 * 생산 경로가 다시 {@code config/} 를 읽게 되는 것을 막는다.
	 *
	 * <p>{@link PerkRegistry#load(Path)} 는 시험이 임의의 풀을 주입하는 주입구로 남아 있다.
	 * 편리해 보여서 {@code SharedFateMod} 가 그것을 다시 부르기 쉬운데, 그 순간 판마다 정의
	 * 파일을 손으로 지워야 하는 옛 사고가 통째로 돌아온다.
	 *
	 * <p>클래스 파일의 상수 풀을 그대로 뒤진다 — {@code SharedFateMod} 가 옛 파일 이름을
	 * 직접 들고 있으면 안 되고({@link LegacyDefinitionFiles} 가 대신 들고 있다), 번들을 읽는
	 * 메서드는 반드시 불러야 한다.
	 */
	@Test
	void 생산_경로는_설정_폴더의_정의_파일을_보지_않는다() throws IOException {
		String bytes = classBytes(SharedFateMod.class);
		assertTrue(bytes.contains("loadBundled"),
				"SharedFateMod 가 모드 안의 정의를 읽지 않는다");
		assertFalse(bytes.contains(PerkRegistry.FILE_NAME),
				"SharedFateMod 가 " + PerkRegistry.FILE_NAME + " 을 직접 가리킨다. "
						+ "정의는 모드 안에서만 읽어야 한다");
		assertFalse(bytes.contains(PerkSetRegistry.FILE_NAME),
				"SharedFateMod 가 " + PerkSetRegistry.FILE_NAME + " 을 직접 가리킨다");
	}

	/** 번들 리소스가 실제로 JAR 안에 들어 있는지. 빠지면 증강이 통째로 사라진다. */
	@Test
	void 번들_정의가_모드_안에_들어_있다() throws IOException {
		for (String resource : new String[] {
				"/sharedfate-perks-default.json", "/sharedfate-sets-default.json"}) {
			try (InputStream in = PerkRegistry.class.getResourceAsStream(resource)) {
				assertTrue(in != null && in.readAllBytes().length > 0,
						resource + " 가 모드 리소스에 없다");
			}
		}
	}

	private static String classBytes(Class<?> type) throws IOException {
		String path = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("클래스 파일을 찾지 못했습니다: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
