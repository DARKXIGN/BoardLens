# Data collection protocol (draft)

Applies to any real classroom footage used for training or evaluation.

## Before recording

- Written permission from the instructor for the specific lecture(s), and agreement to share the original slide files for alignment.
- If the data is collected as part of lab research involving people (user studies, interviews, or footage where people may appear), it goes through the lab's IRB process first. Do not start a study on your own.

## While recording

- Frame the board or screen only. No faces; if a person enters the frame, discard those frames.
- No audio.
- Log per session: room, seat row, measured distance to the board (m), approximate viewing angle, lighting, phone model, zoom ratio.

## After recording

- Review every frame and delete any that contain a person, screen of a personal device, or other private information.
- Store raw footage outside the git repository (e.g. `data/real/`, which is git-ignored). Commit only code, configs, and aggregate results.
- Keep the mapping from frame IDs to sessions in `data/real/sessions.csv`.
