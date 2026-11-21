package dev.migzu.handyphone.client.os;

/** Mola amortecida simples (semi-implícita), independente da taxa de quadros. */
public final class Spring {
	public double value;
	public double target;
	public double velocity;
	private final double stiffness;
	private final double damping;

	public Spring(double initial, double stiffness, double damping) {
		this.value = initial;
		this.target = initial;
		this.stiffness = stiffness;
		this.damping = damping;
	}

	/** Mola "viva", com leve ultrapassagem. */
	public static Spring bouncy(double initial) {
		return new Spring(initial, 260, 22);
	}

	/** Mola suave, sem ultrapassagem. */
	public static Spring smooth(double initial) {
		return new Spring(initial, 210, 29);
	}

	public void step(double dt) {
		double remaining = Math.min(dt, 0.1);
		while (remaining > 1e-6) {
			double h = Math.min(remaining, 1.0 / 240.0);
			double accel = stiffness * (target - value) - damping * velocity;
			velocity += accel * h;
			value += velocity * h;
			remaining -= h;
		}
		if (isResting()) {
			value = target;
			velocity = 0;
		}
	}

	public boolean isResting() {
		return Math.abs(target - value) < 5e-4 && Math.abs(velocity) < 5e-3;
	}

	public void snap(double v) {
		value = v;
		target = v;
		velocity = 0;
	}

	public float f() {
		return (float) value;
	}
}
