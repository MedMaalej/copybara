/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.transform.metadata;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Jimfs;
import com.google.copybara.authoring.Author;
import com.google.copybara.Change;
import com.google.copybara.ChangeVisitable;
import com.google.copybara.Changes;
import com.google.copybara.Destination.Reader;
import com.google.copybara.Metadata;
import com.google.copybara.MigrationInfo;
import com.google.copybara.RepoException;
import com.google.copybara.TransformWork;
import com.google.copybara.ValidationException;
import com.google.copybara.testing.DummyOrigin;
import com.google.copybara.testing.DummyReference;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.util.console.testing.TestingConsole;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.re2j.Pattern;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unittests for the @link{ReferenceMigrator} and {@link MetadataModule#UPDATE_REFS}.
 */
@RunWith(JUnit4.class)
public class ReferenceMigratorTest {

  private DummyOrigin origin;
  private ChangeVisitable<?> destinationReader;
  private ReferenceMigrator referenceMigrator;
  private SkylarkTestExecutor skylark;
  private TestingConsole console;
  private Path checkoutDir;
  private Location location;

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    FileSystem fs = Jimfs.newFileSystem();
    checkoutDir = fs.getPath("/test-checkoutDir");
    origin = new DummyOrigin();
    destinationReader = new MockReader();
    location = new Location(1, 2){
      @Override
      public PathFragment getPath() {
        return null;
      }
    };
    referenceMigrator = ReferenceMigrator.create(
        "http://internalReviews.com/${reference}",
        "http://externalreviews.com/view?${reference}",
        Pattern.compile("[0-9]+"),
        Pattern.compile("[0-9a-f]+"),
        ImmutableList.<String>of(),
        location);
     OptionsBuilder options = new OptionsBuilder();
    console = new TestingConsole();
    options.setConsole(console);
    skylark = new SkylarkTestExecutor(options, MetadataModule.class);

  }

  private TransformWork getTransformWork(String msg) {
    return new TransformWork(checkoutDir, new Metadata(msg, new Author("foo", "foo@foo.com")),
        new Changes() {
          @Override
          public SkylarkList<? extends Change<?>> getCurrent() {
            throw new UnsupportedOperationException();
          }

          @Override
          public SkylarkList<? extends Change<?>> getMigrated() {
            throw new UnsupportedOperationException();
          }
        }, console, new MigrationInfo(DummyOrigin.LABEL_NAME, destinationReader));
  }

  @Test
  public void testReferenceGetsUpdated() throws Exception {
    String desc = "This is an awesome change, building on http://internalReviews.com/123";
    TransformWork work = getTransformWork(desc);
    referenceMigrator.transform(work);
    assertThat(work.getMessage())
        .isEqualTo("This is an awesome change, building on http://externalreviews.com/view?7b");
  }

  @Test
  public void testUndefinedReferenceGetsNotUpdated() throws Exception {
    String desc = "This is an awesome change, building on http://internalReviews.com/5";
    TransformWork work = getTransformWork(desc);
    referenceMigrator.transform(work);
    assertThat(work.getMessage())
        .isEqualTo("This is an awesome change, building on http://internalReviews.com/5");
  }

  @Test
  public void testOldReferenceGetsNotUpdated() throws Exception {
    String desc = "This is an awesome change, building on http://internalReviews.com/110";
    TransformWork work = getTransformWork(desc);
    referenceMigrator.transform(work);
    assertThat(work.getMessage())
        .isEqualTo("This is an awesome change, building on http://internalReviews.com/110");
  }

  @Test
  public void testMultipleGetUpdated() throws Exception {
    String desc = "This is an awesome change, building on http://internalReviews.com/5005, "
        + "http://internalReviews.com/53, http://internalReviews.com/5, "
        + "http://internalReviews.com/14 and stuff.";
    TransformWork work = getTransformWork(desc);
    referenceMigrator.transform(work);
    assertThat(work.getMessage())
        .isEqualTo("This is an awesome change, building on http://internalReviews.com/5005, "
            + "http://externalreviews.com/view?35, http://internalReviews.com/5, "
            + "http://externalreviews.com/view?e and stuff.");
  }

  @Test
  public void testLegacyLabel() throws Exception {
    referenceMigrator = ReferenceMigrator.create(
        "http://internalReviews.com/${reference}",
        "http://externalreviews.com/view?${reference}",
        Pattern.compile("[0-9]+"),
        Pattern.compile("[0-9a-f]+"),
        ImmutableList.<String>of("LegacyImporter"),
        location);
    String desc = "This is an awesome change, building on http://internalReviews.com/123";
    TransformWork work = getTransformWork(desc);
    referenceMigrator.transform(work);
    assertThat(work.getMessage())
        .isEqualTo("This is an awesome change, building on http://externalreviews.com/view?7b");
  }



  @Test
  public void testReverseRegexEnforced() throws Exception {
    String desc = "This is an awesome change, building on http://internalReviews.com/123";
    referenceMigrator = ReferenceMigrator.create(
        "http://internalReviews.com/${reference}",
        "http://externalreviews.com/view?${reference}",
        Pattern.compile("[0-9]+"),
        Pattern.compile("[xyz]+"),
        ImmutableList.<String>of(),
        location);
    TransformWork work = getTransformWork(desc);
    thrown.expect(ValidationException.class);
    thrown.expectMessage("Reference 7b does not match regex '[xyz]+'");
    referenceMigrator.transform(work);
  }

  @Test
  public void testMigratorParses() throws Exception {
    ReferenceMigrator migrator = skylark.eval("result", ""
        + "result = metadata.map_references(\n"
        + "    before = \"origin/\\${reference}\",\n"
        + "    after = \"destination/\\${reference}\",\n"
        + "    regex_groups = {"
        + "        \"before_ref\": \"[0-9a-f]+\",\n"
        + "    },\n"
        + ")");
    assertThat(migrator).isNotNull();
  }

  @Test
  public void testBeforeRefRequired() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${other}\",\n"
            + "    after = \"destination/\\${reference}\",\n"
            + "    regex_groups = {"
            + "        \"after_ref\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        "Invalid 'regex_groups' - Should only contain 'before_ref' and optionally 'after_ref'. "
            + "Was: \\[after_ref\\]");
  }

  @Test
  public void testAfterRefParses() throws Exception {
    ReferenceMigrator migrator = skylark.eval("result", ""
        + "result = metadata.map_references(\n"
        + "    before = \"origin/\\${reference}\",\n"
        + "    after = \"destination/\\${reference}\",\n"
        + "    regex_groups = {"
        + "        \"before_ref\": \"[0-9a-f]+\",\n"
        + "        \"after_ref\": \"[0-9a-f]+\",\n"
        + "    },\n"
        + ")");
    assertThat(migrator).isNotNull();
  }

  @Test
  public void testAdditionalGroupFails() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${other}\",\n"
            + "    after = \"destination/\\${reference}\",\n"
            + "    regex_groups = {"
            + "        \"after_ref\": \"[0-9a-f]+\",\n"
            + "        \"I_do_not_belong_here\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        "Should only contain 'before_ref' and optionally 'after_ref'. "
            + "Was: \\[.*I_do_not_belong_here.*\\].");
  }

  @Test
  public void testOriginPatternNeedsGroup() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${other}\",\n"
            + "    after = \"destination/\\${reference}\",\n"
            + "    regex_groups = {"
            + "        \"before_ref\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        "Interpolation is used but not defined: other");
  }

  @Test
  public void testOriginPatternHasMultipleGroup() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${reference}${other}\",\n"
            + "    after = \"destination/\\${reference}\",\n"
            + "    regex_groups = {"
            + "        \"before_ref\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        "Interpolation is used but not defined.");
  }

  @Test
  public void testDestinationFormatFailsMultipleGroup() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${reference}\",\n"
            + "    after = \"destination/\\${reference}\\${other}\",\n"
            + "    regex_groups = {"
            + "        \"before_ref\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        "Interpolation is used but not defined: other");
  }

  @Test
  public void testDestinationFormatNeedsGroup() throws Exception {
    skylark.evalFails(""
        + "metadata.map_references(\n"
        + "    before = \"origin/\\${reference}\\${other}\",\n"
        + "    after = \"destination/\\${other}\",\n"
        + "    regex_groups = {"
        + "        \"before_ref\": \"[0-9a-f]+\",\n"
        + "    },\n"
        + ")",
    "Interpolation is used but not defined: other");
  }

  @Test
  public void testDestinationFormatBannedToken() throws Exception {
    skylark.evalFails(""
            + "metadata.map_references(\n"
            + "    before = \"origin/\\${reference}\",\n"
            + "    after = \"destination/\\${reference}$$1\",\n"
            + "    regex_groups = {"
            + "        \"before_ref\": \"[0-9a-f]+\",\n"
            + "    },\n"
            + ")",
        " uses the reserved token");
  }

  class MockReader implements Reader<DummyReference> {
    @Override
    public void visitChanges(DummyReference start, ChangesVisitor visitor)
        throws RepoException {
      int changeNumber = 0;
      Change<DummyReference> change;
      do {
        changeNumber++;
        ImmutableMap.Builder<String, String> labels = ImmutableMap.builder();
        String destinationId = Integer.toHexString(changeNumber);

        if (changeNumber % 5 != 0) {
          labels.put(origin.getLabelName(), "" + changeNumber);
        }
        if (changeNumber % 11 == 0) {
          labels.put("LegacyImporter", "" + changeNumber);
        }
        change = new Change<>(new DummyReference(destinationId),
            new Author("Foo", "Bar"),
            "Lorem Ipsum", ZonedDateTime.now(),
            labels.build());

      } while (visitor.visit(change) != VisitResult.TERMINATE);
    }
  }
}
