// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{
    command::CommandResult, error::*, generator::Format, ir::ir2::IR2, technique::Technique,
};
use std::path::Path;
use std::{convert::From, path::PathBuf};

pub struct JSON;

impl Generator for JSON {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &IR2,
        _source_file: &str,
        dest_file: Option<&Path>,
        _policy_metadata: bool,
    ) -> Result<Vec<CommandResult>> {
        let content = Technique::from_ir(gc)?.to_json()?;
        Ok(vec![CommandResult::new(
            Format::JSON,
            match dest_file {
                Some(path) => path.to_str().map(PathBuf::from),
                None => None,
            },
            Some(content.to_string()),
        )])
    }
}
