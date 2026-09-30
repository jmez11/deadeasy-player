package com.jmez11.deadeasyplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProjectionModeDetectorTest(
    private val filename: String,
    private val expectedMode: ProjectionMode
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: detectProjectionMode(\"{0}\") = {1}")
        fun data(): Collection<Array<Any>> = listOf(
            // README promised tags with dots
            arrayOf("movie.SBS.mkv", ProjectionMode.SBS),
            arrayOf("movie.HSBS.mkv", ProjectionMode.SBS),
            arrayOf("movie.OU.mkv", ProjectionMode.OU),
            arrayOf("movie.HOU.mkv", ProjectionMode.OU),

            // Mixed case & 3D tags
            arrayOf("Movie.3d.SbS.mkv", ProjectionMode.SBS),
            arrayOf("Avatar.3D.hsbs.1080p.mkv", ProjectionMode.SBS),
            arrayOf("Documentary.Ou.mp4", ProjectionMode.OU),
            arrayOf("Nature.hOu.mkv", ProjectionMode.OU),

            // Underscore delimiters
            arrayOf("movie_sbs_1080p.mkv", ProjectionMode.SBS),
            arrayOf("movie_HSBS_remux.mp4", ProjectionMode.SBS),
            arrayOf("clip_ou_4k.mp4", ProjectionMode.OU),
            arrayOf("demo_hou_hdr.mkv", ProjectionMode.OU),

            // Dash delimiters
            arrayOf("movie-sbs-vr.mkv", ProjectionMode.SBS),
            arrayOf("trailer-hsbs.mp4", ProjectionMode.SBS),
            arrayOf("film-ou-3d.mkv", ProjectionMode.OU),
            arrayOf("test-hou.mp4", ProjectionMode.OU),

            // Space delimiters
            arrayOf("Movie SBS 3D.mkv", ProjectionMode.SBS),
            arrayOf("Movie HSBS 2024.mkv", ProjectionMode.SBS),
            arrayOf("Movie OU Cinema.mkv", ProjectionMode.OU),
            arrayOf("Movie HOU VR.mkv", ProjectionMode.OU),

            // Start or end of filename without leading/trailing delimiter
            arrayOf("SBS.mp4", ProjectionMode.SBS),
            arrayOf("HSBS.mkv", ProjectionMode.SBS),
            arrayOf("OU.mp4", ProjectionMode.OU),
            arrayOf("HOU.mkv", ProjectionMode.OU),
            arrayOf("movie_sbs", ProjectionMode.SBS),
            arrayOf("movie-ou", ProjectionMode.OU),

            // Plain 2D names
            arrayOf("BigBuckBunny.mp4", ProjectionMode.MONO),
            arrayOf("Inception.2010.1080p.mkv", ProjectionMode.MONO),
            arrayOf("family_vacation_2023.mov", ProjectionMode.MONO),
            arrayOf("sample-video.mp4", ProjectionMode.MONO),

            // Tricky names (must NOT false positive)
            arrayOf("output.mp4", ProjectionMode.MONO),
            arrayOf("subtitles.srt", ProjectionMode.MONO),
            arrayOf("route.mp4", ProjectionMode.MONO),
            arrayOf("continuous.mkv", ProjectionMode.MONO),
            arrayOf("asbestos.mp4", ProjectionMode.MONO),
            arrayOf("south_park_s01e01.mkv", ProjectionMode.MONO),
            arrayOf("loudness_audio.mp4", ProjectionMode.MONO)
        )
    }

    @Test
    fun testDetectProjectionMode() {
        val actualMode = detectProjectionMode(filename)
        assertEquals("Failed detection for $filename", expectedMode, actualMode)
    }
}
