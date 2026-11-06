"""Cross-module consistency checks for the P2 profile sets.

The same module set is declared in several places (host aggregation, API baselines, device
instrumentation, performance criteria, behavior matrix, release admission). Each copy is
deliberate - they serve different gates - but a drift between them silently narrows or widens
admission. These tests fail as soon as one copy disagrees.
"""
import unittest

import gates
import p2_api
import p2_gates
import p2_matrix
import p2_performance


class ProfileSetConsistencyTest(unittest.TestCase):
    def test_host_aggregation_matches_api_baselines(self):
        # p2_api covers the codegen processor jars too; p2_gates aggregates them under "codegen".
        self.assertEqual(set(p2_gates.MODULES) | {"codegen"}, set(p2_api.MODULES) - {"codegen-api"})

    def test_host_profiles_are_modules_plus_codegen(self):
        self.assertEqual(p2_gates.PROFILES, set(p2_gates.MODULES) | {"codegen"})

    def test_release_admission_covers_every_host_profile(self):
        # Two independent copies on purpose: gates.ALLOWED_P2 is what release() enforces, while the
        # literal below is the reviewed expectation. A drift in either one fails here.
        reviewed = {"plan", "codegen", "recycler", "text", "layouts", "prewarm", "frame-shared"}
        self.assertEqual(set(gates.ALLOWED_P2), reviewed)
        self.assertEqual(set(gates.ALLOWED_P2), p2_gates.PROFILES)

    def test_performance_criteria_declare_every_admitted_module(self):
        self.assertEqual(set(p2_performance.PROFILES), p2_gates.PROFILES)

    def test_device_matrix_covers_every_admitted_module(self):
        self.assertEqual(set(p2_matrix.PROFILES), p2_gates.PROFILES)

    def test_admitted_modules_bind_their_artifacts(self):
        # A performance receipt that does not bind the module artifact cannot prove which build
        # was measured.
        for module in p2_gates.MODULES:
            artifact = "ui-frame-shared" if module == "frame-shared" else "ui-" + module
            self.assertTrue(
                any(artifact in name for name in p2_performance.ADMITTED_MODULES),
                "performance admission does not bind " + artifact,
            )


if __name__ == "__main__":
    unittest.main()
