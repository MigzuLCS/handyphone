package dev.migzu.handyphone.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Gravação em disco que não deixa meio arquivo para trás se o servidor cair no meio. */
final class DiskIo {
	private DiskIo() {
	}

	/**
	 * Troca {@code target} por {@code temp} de uma vez só (sem janela em que o arquivo some). Se o sistema de
	 * arquivos não aceita troca atômica, cai para a troca comum.
	 */
	static void moveAtomic(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
