import numpy as np

from fogsim import Params, simulate


def test_clear_weather_careful_drivers_do_not_crash():
    p = Params(visibility_m=300, speed_kmh=(40, 70), headway_s=(1.2, 2.5), reaction_s=(0.8, 1.5))
    assert all(simulate(p, seed=s).collisions == 0 for s in range(5))


def test_dense_fog_without_warning_causes_a_pileup():
    r = simulate(Params(visibility_m=25, equipped=0.0), seed=1)
    assert r.collisions >= 10
    assert r.warned == 0


def test_full_adoption_reduces_collisions():
    base = np.mean([simulate(Params(visibility_m=40, equipped=0.0), seed=s).collisions for s in range(6)])
    full = np.mean([simulate(Params(visibility_m=40, equipped=1.0), seed=s).collisions for s in range(6)])
    assert full < 0.5 * base


def test_warnings_travel_back_along_the_queue():
    r = simulate(Params(visibility_m=40, equipped=1.0, n_vehicles=30), seed=2)
    assert r.warned >= 25  # relays carry it far beyond one radio range
    assert r.first_warning_s is not None


def test_no_radio_no_benefit():
    a = simulate(Params(visibility_m=40, equipped=0.0), seed=3).collisions
    b = simulate(Params(visibility_m=40, equipped=1.0, radio_range_m=0.0), seed=3).collisions
    assert a == b
